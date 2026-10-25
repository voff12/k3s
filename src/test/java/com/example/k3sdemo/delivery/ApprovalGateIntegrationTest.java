package com.example.k3sdemo.delivery;

import com.example.k3sdemo.delivery.dto.CreateApplicationRequest;
import com.example.k3sdemo.delivery.dto.PolicyTemplateConfig;
import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.entity.Artifact;
import com.example.k3sdemo.delivery.entity.PolicyTemplate;
import com.example.k3sdemo.delivery.entity.Release;
import com.example.k3sdemo.delivery.repository.ApplicationRepository;
import com.example.k3sdemo.delivery.repository.ArtifactRepository;
import com.example.k3sdemo.delivery.service.ApplicationService;
import com.example.k3sdemo.delivery.service.PipelineTrigger;
import com.example.k3sdemo.delivery.service.PolicyTemplateService;
import com.example.k3sdemo.delivery.service.ReleasePersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2 审批门集成测试：PROD 发布且策略 requireProdApproval=true →
 * 挂起等待审批；approve 后放行；发起人自审被拒。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalGateIntegrationTest {

    @org.junit.jupiter.api.AfterEach
    void cleanUp() throws Exception {
        // 先等异步流水线写完（终态非 RUNNING），避免清理后仍有线程回写
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            var statuses = jdbcTemplate.queryForList(
                    "SELECT status FROM delivery_release", String.class);
            boolean running = statuses.stream().anyMatch(
                    s -> "RUNNING".equals(s) || "PENDING".equals(s) || "AWAITING_APPROVAL".equals(s));
            if (!running) { break; }
            Thread.sleep(100);
        }
        // 关闭引用完整性后清表（顺序无关），防止异步残写造成外键冲突
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        String[] tables = {
                "activity_log", "ai_analysis", "quality_event", "approval", "rollout_step",
                "release_stage", "delivery_release", "release_no_seq", "pipeline_run",
                "artifact", "app_environment", "application", "policy_template"};
        for (String table : tables) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        // 重播 V1 内置种子（DeliveryApiIntegrationTest 依赖 3 档内置模板）
        jdbcTemplate.execute("INSERT INTO policy_template (name, code, description, config_json, builtin, deleted) VALUES "
                + "('低风险', 'LOW', '自动开始灰度；规则通过后自动扩流', "
                + "'{\"autoCanary\":true,\"requireProdApproval\":false,\"canarySteps\":[5,20,50,100],\"autoRollback\":true}', TRUE, FALSE), "
                + "('标准', 'STANDARD', 'Beta 验收后灰度；异常自动暂停', "
                + "'{\"autoCanary\":false,\"requireProdApproval\":false,\"canarySteps\":[5,20,50,100],\"autoRollback\":true}', TRUE, FALSE), "
                + "('高风险', 'HIGH', '生产灰度需负责人批准；硬阈值可回退', "
                + "'{\"autoCanary\":false,\"requireProdApproval\":true,\"canarySteps\":[5,20,50,100],\"autoRollback\":true}', TRUE, FALSE)");
        fakeTrigger.ids.clear();
    }

    static class FakeTrigger implements PipelineTrigger {
        final ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<>();
        volatile Map<String, Object> nextStatus = Map.of(
                "status", "SUCCESS", "finished", true,
                "imageRef", "harbor.local/library/gate-svc:rel-1");

        @Override
        public String trigger(String gitUrl, String branch, String imageName,
                              String deployment, String namespace) {
            String id = "fake-" + ids.size();
            ids.add(id);
            return id;
        }

        @Override
        public Map<String, Object> poll(String pipelineId) {
            return nextStatus;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeTrigger fakeTrigger() {
            return new FakeTrigger();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ArtifactRepository artifactRepository;
    @Autowired
    private ReleasePersistenceService persistenceService;
    @Autowired
    private PolicyTemplateService policyTemplateService;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired
    private com.example.k3sdemo.delivery.repository.ReleaseApprovalRepository releaseApprovalRepository;
    @Autowired
    private FakeTrigger fakeTrigger;

    private Long newProdReleaseWithApprovalRequired() {
        CreateApplicationRequest req = new CreateApplicationRequest();
        req.setName("支付服务");
        req.setRepoUrl("https://git.example.com/pay/pay-service-"
                + System.nanoTime() + ".git");
        req.setRuntimeType("java");
        Application app = applicationService.create(req);
        // 应用级策略模板：要求 PROD 人工审批
        PolicyTemplateConfig requireApproval = new PolicyTemplateConfig();
        requireApproval.setRequireProdApproval(true);
        PolicyTemplate template = policyTemplateService.create(new PolicyTemplateService.CreateRequest(
                "支付专属策略-" + System.nanoTime(), null,
                requireApproval, "测试员"));
        app.setPolicyTemplateId(template.getId());
        applicationRepository.save(app);
        Artifact artifact = new Artifact();
        artifact.setAppId(app.getId());
        artifact.setVersion("v1.0.0");
        artifact.setGitSha("b".repeat(40));
        artifact.setGitBranch("main");
        artifact.setImageRepo("harbor.local/library/pay-service");
        artifact.setImageDigest("sha256:" + "c".repeat(64));
        artifactRepository.save(artifact);
        Release release = persistenceService.create(
                app.getId(), artifact.getId(), "PROD", "CANARY", null, "吴工");
        return release.getId();
    }

    @Test
    void prodReleaseWithoutApprovalHangsAtApprovalGate() throws Exception {
        Long releaseId = newProdReleaseWithApprovalRequired();
        // 触发执行：应停在审批门
        mockMvc.perform(post("/api/delivery/releases/" + releaseId + "/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentStage").value("AWAITING_APPROVAL"));
        // PENDING 审批记录存在
        assertThat(releaseApprovalRepository
                .findByReleaseIdAndGateAndStatus(releaseId, "PROD_START", "PENDING"))
                .isPresent();
        // 发起人自审自批被拒
        mockMvc.perform(post("/api/delivery/releases/" + releaseId + "/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gate\":\"PROD_START\",\"approve\":true,\"approver\":\"吴工\"}"))
                .andExpect(status().isForbidden());
        // 管理员审批通过 → 自动放行，进入执行
        mockMvc.perform(post("/api/delivery/releases/" + releaseId + "/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gate\":\"PROD_START\",\"approve\":true,\"approver\":\"李总\"}"))
                .andExpect(status().isOk());
        assertThat(fakeTrigger.ids).isNotEmpty();
    }
}
