package com.example.k3sdemo.delivery;

import com.example.k3sdemo.delivery.dto.CreateApplicationRequest;
import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.entity.Artifact;
import com.example.k3sdemo.delivery.entity.Release;
import com.example.k3sdemo.delivery.entity.ReleaseStage;
import com.example.k3sdemo.delivery.repository.AppEnvironmentRepository;
import com.example.k3sdemo.delivery.repository.ArtifactRepository;
import com.example.k3sdemo.delivery.repository.ReleaseRepository;
import com.example.k3sdemo.delivery.service.ApplicationService;
import com.example.k3sdemo.delivery.service.ReleasePersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1+P2 主链路测试：接入应用落库 → 登记制品 → 创建发布（6 阶段落库）→ 阶段推进。
 * 跑在 H2(MySQL 兼容模式) 上，验证 Flyway V1 迁移与实体映射一致。
 */
@SpringBootTest
@Transactional
class DeliveryCenterIntegrationTest {

    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ReleasePersistenceService releasePersistenceService;
    @Autowired
    private AppEnvironmentRepository appEnvironmentRepository;
    @Autowired
    private ArtifactRepository artifactRepository;
    @Autowired
    private ReleaseRepository releaseRepository;

    private Application onboardApp() {
        CreateApplicationRequest req = new CreateApplicationRequest();
        req.setName("订单服务");
        req.setRepoUrl("https://git.example.com/trade/order-service.git");
        req.setTeam("trade");
        req.setDefaultBranch("main");
        req.setRuntimeType("java");
        req.setPort(8080);
        req.setNamespace("team-apps");
        req.setHealthPath("/actuator/health/readiness");
        req.setPolicyTemplateCode("STANDARD");
        req.setEnvironments(List.of("PREVIEW", "BETA"));
        req.setOperatorName("吴工");
        return applicationService.create(req);
    }

    private Artifact registerArtifact(Application app) {
        Artifact artifact = new Artifact();
        artifact.setAppId(app.getId());
        artifact.setVersion("v2.8.0");
        artifact.setGitSha("4c18fa2aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        artifact.setGitBranch("main");
        artifact.setPrNumber(479);
        artifact.setPrTitle("优化库存扣减");
        artifact.setImageRepo("harbor.internal/library/order-service");
        artifact.setImageDigest("sha256:94e7aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        artifact.setScanStatus("PASSED");
        artifact.setSbomStatus("GENERATED");
        return artifactRepository.save(artifact);
    }

    @Test
    void onboardApplication_persistsAppAndEnvironments() {
        Application app = onboardApp();

        assertThat(app.getId()).isNotNull();
        assertThat(app.getCode()).isEqualTo("order-service");
        assertThat(app.getProdEnabled()).isFalse();
        assertThat(appEnvironmentRepository.findByAppId(app.getId()))
                .extracting("env")
                .containsExactlyInAnyOrder("PREVIEW", "BETA");
    }

    @Test
    void onboardApplication_duplicateCodeRejected() {
        onboardApp();
        assertThatThrownBy(this::onboardApp)
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void createRelease_persistsSixStages() {
        Application app = onboardApp();
        Artifact artifact = registerArtifact(app);

        Release release = releasePersistenceService.create(
                app.getId(), artifact.getId(), "PRODUCTION", "CANARY",
                "优化库存扣减并减少数据库锁等待。", "吴工");

        assertThat(release.getId()).isNotNull();
        assertThat(release.getReleaseNo()).startsWith("rel-");
        assertThat(release.getEnv()).isEqualTo("PROD");
        assertThat(release.getStatus()).isEqualTo("PENDING");

        List<ReleaseStage> stages = releasePersistenceService.stages(release.getId());
        assertThat(stages).hasSize(6);
        assertThat(stages).extracting("stageCode")
                .containsExactly("PR_CHECK", "BUILD", "PREVIEW", "BETA", "CANARY", "STABLE");
        assertThat(stages).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo("PENDING"));
    }

    @Test
    void createRelease_releaseNoIsDailySequence() {
        Application app = onboardApp();
        Artifact artifact = registerArtifact(app);
        // 往日的发布不应占用今天的序号
        Release pastRelease = new Release();
        pastRelease.setReleaseNo("rel-20000101-001");
        pastRelease.setAppId(app.getId());
        pastRelease.setArtifactId(artifact.getId());
        pastRelease.setEnv("PROD");
        pastRelease.setStrategy("CANARY");
        releaseRepository.save(pastRelease);

        Release first = releasePersistenceService.create(
                app.getId(), artifact.getId(), "PROD", "CANARY", null, "吴工");
        Release second = releasePersistenceService.create(
                app.getId(), artifact.getId(), "PROD", "CANARY", null, "吴工");

        String todayPrefix = "rel-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        assertThat(first.getReleaseNo()).isEqualTo(todayPrefix + "001");
        assertThat(second.getReleaseNo()).isEqualTo(todayPrefix + "002");
    }

    @Test
    void advanceStage_updatesStageAndRelease() {
        Application app = onboardApp();
        Artifact artifact = registerArtifact(app);
        Release release = releasePersistenceService.create(
                app.getId(), artifact.getId(), "PROD", "CANARY", null, "吴工");

        releasePersistenceService.advanceStage(release.getId(), 1, "RUNNING");
        releasePersistenceService.advanceStage(release.getId(), 1, "PASSED");

        Release after = releasePersistenceService.get(release.getId());
        assertThat(after.getCurrentStage()).isEqualTo("PR_CHECK");
        assertThat(after.getStatus()).isEqualTo("RUNNING");
        assertThat(after.getStartedAt()).isNotNull();

        releasePersistenceService.advanceStage(release.getId(), 6, "PASSED");
        Release finished = releasePersistenceService.get(release.getId());
        assertThat(finished.getStatus()).isEqualTo("SUCCESS");
        assertThat(finished.getFinishedAt()).isNotNull();
    }

    @Test
    void createRelease_artifactOfOtherAppRejected() {
        Application app = onboardApp();
        // 另一个应用：不同仓库地址 → 不同 code，避免唯一键冲突
        CreateApplicationRequest otherReq = new CreateApplicationRequest();
        otherReq.setName("支付服务");
        otherReq.setRepoUrl("https://git.example.com/trade/payment-service.git");
        otherReq.setTeam("trade");
        otherReq.setOperatorName("吴工");
        Application other = applicationService.create(otherReq);
        Artifact artifact = registerArtifact(other);

        assertThatThrownBy(() -> releasePersistenceService.create(
                app.getId(), artifact.getId(), "PROD", "CANARY", null, "吴工"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
