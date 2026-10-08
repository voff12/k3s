package com.example.k3sdemo.delivery;

import com.example.k3sdemo.delivery.dto.CreateApplicationRequest;
import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.entity.Artifact;
import com.example.k3sdemo.delivery.entity.Release;
import com.example.k3sdemo.delivery.repository.ArtifactRepository;
import com.example.k3sdemo.delivery.service.ApplicationService;
import com.example.k3sdemo.delivery.service.ReleasePersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交付中心只读 API 集成测试：activities / artifacts / settings。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DeliveryApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ArtifactRepository artifactRepository;
    @Autowired
    private ReleasePersistenceService releasePersistenceService;

    private Application onboardApp() {
        CreateApplicationRequest req = new CreateApplicationRequest();
        req.setName("订单服务");
        req.setRepoUrl("https://git.example.com/trade/order-service.git");
        req.setOperatorName("吴工");
        return applicationService.create(req);
    }

    private Artifact registerArtifact(Application app) {
        Artifact artifact = new Artifact();
        artifact.setAppId(app.getId());
        artifact.setVersion("v2.8.0");
        artifact.setGitSha("4c18fa2aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        artifact.setImageRepo("harbor.internal/library/order-service");
        artifact.setImageDigest("sha256:94e7aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        artifact.setScanStatus("PASSED");
        artifact.setSbomStatus("GENERATED");
        return artifactRepository.save(artifact);
    }

    @Test
    void activities_returnsReleaseCreatedLog() throws Exception {
        Application app = onboardApp();
        Artifact artifact = registerArtifact(app);
        releasePersistenceService.create(
                app.getId(), artifact.getId(), "PRODUCTION", "CANARY", null, "吴工");

        mockMvc.perform(get("/api/delivery/activities").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].action").value("RELEASE_CREATED"))
                .andExpect(jsonPath("$.data[0].message").exists());
    }

    @Test
    void artifacts_returnsOnlyThatAppsArtifacts() throws Exception {
        Application app = onboardApp();
        registerArtifact(app);

        mockMvc.perform(get("/api/delivery/artifacts").param("appId", String.valueOf(app.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].version").value("v2.8.0"))
                .andExpect(jsonPath("$.data[0].scanStatus").value("PASSED"));
    }

    @Test
    void artifacts_rejectsMissingAppId() throws Exception {
        mockMvc.perform(get("/api/delivery/artifacts"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void appCatalog_includesEnvironments() throws Exception {
        CreateApplicationRequest req = new CreateApplicationRequest();
        req.setName("订单服务");
        req.setRepoUrl("https://git.example.com/trade/order-service.git");
        req.setEnvironments(List.of("PREVIEW", "BETA"));
        req.setOperatorName("吴工");
        applicationService.create(req);

        mockMvc.perform(get("/api/delivery/app-catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("order-service"))
                .andExpect(jsonPath("$.data[0].environments.length()").value(2))
                .andExpect(jsonPath("$.data[0].environments[0]").value("PREVIEW"))
                .andExpect(jsonPath("$.data[0].environments[1]").value("BETA"));
    }

    @Test
    void registerArtifact_persistsAndRejectsUnknownApp() throws Exception {
        Application app = onboardApp();
        String body = """
                {"appId":%d,"version":"v2.9.0","gitSha":"%s","gitBranch":"main",
                 "imageRepo":"harbor.internal/library/order-service",
                 "imageDigest":"sha256:ab12aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "scanStatus":"PASSED"}
                """.formatted(app.getId(), "f".repeat(40));

        mockMvc.perform(post("/api/delivery/artifacts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.version").value("v2.9.0"));

        // 应用不存在 → 404
        String badApp = body.replace("\"appId\":" + app.getId(), "\"appId\":999999");
        mockMvc.perform(post("/api/delivery/artifacts").contentType(MediaType.APPLICATION_JSON).content(badApp))
                .andExpect(status().isNotFound());

        // 缺字段 → 400
        mockMvc.perform(post("/api/delivery/artifacts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appId\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void settings_returnsPolicyTemplatesFromSeed() throws Exception {
        mockMvc.perform(get("/api/delivery/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.policyTemplates.length()").value(3))
                .andExpect(jsonPath("$.data.policyTemplates[0].name").exists())
                .andExpect(jsonPath("$.data.integrations").isArray());
    }

    @Test
    void createApplication_viaHttpIsValidated() throws Exception {
        mockMvc.perform(post("/api/delivery/applications")
                        .contentType("application/json")
                        .content("{\"name\":\"支付服务\",\"repoUrl\":\"https://git.example.com/trade/payment-service.git\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.code").value("payment-service"));
    }
}
