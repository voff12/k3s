package com.example.k3sdemo.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务成功率端点空态契约：本地未配置 prometheus.url → available=false。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SuccessRateUnavailableTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void successRate_returnsUnavailableWhenPrometheusNotConfigured() throws Exception {
        mockMvc.perform(get("/api/delivery/overview/success-rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.reason").value("未配置 prometheus.url"));
    }
}
