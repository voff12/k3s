package com.example.k3sdemo.delivery;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务成功率端点（/api/delivery/overview/success-rate）：本地假 Prometheus 验证
 * instant 成功率 + query_range 趋势折线均被解析、PromQL 被正确 URL 编码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SuccessRateMetricsTest {

    private static HttpServer server;
    private static final java.util.List<String> receivedQueries =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @BeforeAll
    static void startFakePrometheus() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // instant 查询：返回成功率标量 99.85
        server.createContext("/api/v1/query", exchange -> {
            receivedQueries.add(exchange.getRequestURI().getQuery());
            byte[] body = ("{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{},"
                    + "\"value\":[1730000000,\"99.85\"]}]}}").getBytes(StandardCharsets.UTF_8);
            respond(exchange, body);
        });
        // range 查询：返回 3 个趋势点（含一个 NaN 验证过滤）
        server.createContext("/api/v1/query_range", exchange -> {
            receivedQueries.add(exchange.getRequestURI().getQuery());
            byte[] body = ("{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{},"
                    + "\"values\":[[1729990000,\"99.80\"],[1729993600,\"NaN\"],[1729997200,\"99.90\"]]}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            respond(exchange, body);
        });
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    @AfterAll
    static void stopFakePrometheus() {
        if (server != null) {
            server.stop(0);
        }
    }

    @DynamicPropertySource
    static void prometheusUrl(DynamicPropertyRegistry registry) {
        registry.add("prometheus.url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void successRate_returnsRateAndTrendFromPrometheus() throws Exception {
        mockMvc.perform(get("/api/delivery/overview/success-rate?window=24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.window").value("24h"))
                .andExpect(jsonPath("$.data.successRatePct").value(99.85))
                // NaN 点被过滤，只保留 2 个有效趋势点
                .andExpect(jsonPath("$.data.trend.length()").value(2))
                .andExpect(jsonPath("$.data.trend[0][1]").value(99.80))
                .andExpect(jsonPath("$.data.trend[1][1]").value(99.90));

        // PromQL 必须 URL 编码（不含原始 {} 和引号）
        org.assertj.core.api.Assertions.assertThat(receivedQueries).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(receivedQueries).allSatisfy(q ->
                org.assertj.core.api.Assertions.assertThat(q)
                        .doesNotContain("{").doesNotContain("}").doesNotContain("\""));
        // instant 和 range 都应被调用
        org.assertj.core.api.Assertions.assertThat(receivedQueries)
                .anySatisfy(q -> org.assertj.core.api.Assertions.assertThat(q).startsWith("query="));
        org.assertj.core.api.Assertions.assertThat(receivedQueries)
                .anySatisfy(q -> org.assertj.core.api.Assertions.assertThat(q).contains("start=").contains("step="));
    }

    @Test
    void successRate_defaultsTo24hWhenWindowOmitted() throws Exception {
        mockMvc.perform(get("/api/delivery/overview/success-rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.window").value("24h"));
    }

    @Test
    void successRate_rejectsInvalidWindowAsDefault24h() throws Exception {
        mockMvc.perform(get("/api/delivery/overview/success-rate?window=99x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.window").value("99x"))
                // 白名单兜底为 24h，不影响可用性
                .andExpect(jsonPath("$.data.available").value(true));
    }
}
