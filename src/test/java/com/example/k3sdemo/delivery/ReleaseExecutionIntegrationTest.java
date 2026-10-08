package com.example.k3sdemo.delivery;

import com.example.k3sdemo.delivery.dto.CreateApplicationRequest;
import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.entity.Artifact;
import com.example.k3sdemo.delivery.entity.Release;
import com.example.k3sdemo.delivery.entity.ReleaseStage;
import com.example.k3sdemo.delivery.repository.ArtifactRepository;
import com.example.k3sdemo.delivery.repository.ReleaseRepository;
import com.example.k3sdemo.delivery.repository.ReleaseStageRepository;
import com.example.k3sdemo.delivery.service.ApplicationService;
import com.example.k3sdemo.delivery.service.PipelineTrigger;
import com.example.k3sdemo.delivery.service.ReleasePersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P3 桥接测试：execute 触发流水线（假触发器）→ 异步轮询 → 6 阶段推进回写。
 * 注意：不能用 @Transactional——watchPipeline 在独立线程/事务中回写，
 * 必须让 MockMvc 请求真实提交后才对异步线程可见。测试间用唯一仓库地址隔离数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReleaseExecutionIntegrationTest {

    /** 假触发器：记录调用、可脚本化状态序列，避免测试连接真实 K3s */
    static class FakeTrigger implements PipelineTrigger {
        volatile String lastGitUrl;
        volatile String lastBranch;
        volatile String lastImage;
        final ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<>();
        volatile Map<String, Object> nextStatus = Map.of("status", "SUCCESS", "finished", true);

        @Override
        public String trigger(String gitUrl, String branch, String imageName,
                              String deployment, String namespace) {
            lastGitUrl = gitUrl;
            lastBranch = branch;
            lastImage = imageName;
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
    private FakeTrigger fakeTrigger;
    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ArtifactRepository artifactRepository;
    @Autowired
    private ReleasePersistenceService persistenceService;
    @Autowired
    private ReleaseRepository releaseRepository;
    @Autowired
    private ReleaseStageRepository stageRepository;

    private Long newRelease(String env) {
        CreateApplicationRequest req = new CreateApplicationRequest();
        // 唯一仓库地址 → 唯一应用 code，避免无事务回滚时跨测试数据冲突
        req.setName("订单服务");
        req.setRepoUrl("https://git.example.com/trade/order-service-" 
                + System.nanoTime() + ".git");
        req.setRuntimeType("java");
        Application app = applicationService.create(req);

        Artifact artifact = new Artifact();
        artifact.setAppId(app.getId());
        artifact.setVersion("v2.8.0");
        artifact.setGitSha("a".repeat(40));
        artifact.setGitBranch("main");
        artifact.setImageRepo("harbor.local/library/order-service");
        artifact.setImageDigest("sha256:" + java.util.stream.IntStream
                .range(0, 64).mapToObj(i -> String.valueOf("0123456789abcdef".charAt(
                        java.util.concurrent.ThreadLocalRandom.current().nextInt(16))))
                .collect(java.util.stream.Collectors.joining()));
        artifactRepository.save(artifact);

        Release release = persistenceService.create(
                app.getId(), artifact.getId(), env, "CANARY", null, "吴工");
        return release.getId();
    }

    private List<ReleaseStage> stages(Long releaseId) {
        return stageRepository.findByReleaseIdOrderByStageOrderAsc(releaseId);
    }

    @Test
    void execute_notFoundForMissingRelease() throws Exception {
        mockMvc.perform(post("/api/delivery/releases/999999/execute"))
                .andExpect(status().isNotFound());
    }

    @Test
    void execute_prodRelease_advancesThroughStagesToSuccess() throws Exception {
        fakeTrigger.nextStatus = Map.of("status", "SUCCESS", "finished", true);
        Long id = newRelease("PRODUCTION");

        // 触发：立即变 RUNNING，PR_CHECK 过、BUILD 进行中
        mockMvc.perform(post("/api/delivery/releases/{id}/execute", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        assertThat(fakeTrigger.lastGitUrl)
                .startsWith("https://git.example.com/trade/order-service-")
                .endsWith(".git");
        assertThat(fakeTrigger.lastBranch).isEqualTo("main");
        assertThat(fakeTrigger.lastImage).isEqualTo("harbor.local/library/order-service");

        // 异步轮询完成 → SUCCESS
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            Release r = releaseRepository.findById(id).orElseThrow();
            assertThat(r.getStatus()).isEqualTo("SUCCESS");
            assertThat(r.getCurrentTraffic()).isEqualTo(100);
        });

        // 阶段断言：PR_CHECK/BUILD/CANARY/STABLE PASSED，PREVIEW/BETA SKIPPED
        List<ReleaseStage> st = stages(id);
        assertThat(st).extracting("stageCode", "status").containsExactly(
                org.assertj.core.groups.Tuple.tuple("PR_CHECK", "PASSED"),
                org.assertj.core.groups.Tuple.tuple("BUILD", "PASSED"),
                org.assertj.core.groups.Tuple.tuple("PREVIEW", "SKIPPED"),
                org.assertj.core.groups.Tuple.tuple("BETA", "SKIPPED"),
                org.assertj.core.groups.Tuple.tuple("CANARY", "PASSED"),
                org.assertj.core.groups.Tuple.tuple("STABLE", "PASSED"));
    }

    @Test
    void execute_pipelineFailureMarksReleaseFailed() throws Exception {
        Long id = newRelease("BETA");
        fakeTrigger.nextStatus = Map.of("status", "FAILED", "finished", true, "error", "kaniko OOM");

        mockMvc.perform(post("/api/delivery/releases/{id}/execute", id))
                .andExpect(status().isOk());

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            Release r = releaseRepository.findById(id).orElseThrow();
            assertThat(r.getStatus()).isEqualTo("FAILED");
        });
        assertThat(stages(id)).extracting("stageCode", "status").contains(
                org.assertj.core.groups.Tuple.tuple("BUILD", "FAILED"));
    }
}
