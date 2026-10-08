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
 * 运行质量指标端点：本地未配置 prometheus.url → available=false 空态契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QualityMetricsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void quality_returnsUnavailableWhenPrometheusNotConfigured() throws Exception {
        mockMvc.perform(get("/api/delivery/quality"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.reason").exists());
    }
}
