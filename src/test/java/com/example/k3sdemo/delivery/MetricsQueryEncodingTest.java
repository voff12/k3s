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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 指标查询路径测试：本地假 Prometheus（JDK HttpServer）验证
 * PromQL 被正确 URL 编码且响应值被解析——回归 3ad5389 修复的
 * "Illegal character in query" 导致所有查询静默返回 null 的缺陷。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetricsQueryEncodingTest {

    private static HttpServer server;
    private static final java.util.List<String> receivedQueries =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @BeforeAll
    static void startFakePrometheus() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", exchange -> {
            receivedQueries.add(exchange.getRequestURI().getQuery());
            String val = "12.34";
            byte[] body = ("{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{},\"value\":[1730000000,\""
                    + val + "\"]}]}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
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
    void quality_parsesValuesFromPrometheusWithEncodedPromql() throws Exception {
        mockMvc.perform(get("/api/delivery/quality"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.httpRequestsPerSecond").value(12.34));

        // 服务端收到的必须是合法 URL 编码查询（解码后含 PromQL 原文）；
        // 并发查询下只断言"任一请求"满足（lastQuery 会被后续查询覆盖）
        assertThat(receivedQueries).isNotEmpty();
        assertThat(receivedQueries).allSatisfy(q -> {
            assertThat(q).startsWith("query=");
            assertThat(q).doesNotContain("{").doesNotContain("}").doesNotContain("\"");
        });
        assertThat(receivedQueries).anySatisfy(q -> assertThat(
                java.net.URLDecoder.decode(q, StandardCharsets.UTF_8))
                .contains("http_server_requests_seconds_count"));
    }
}
