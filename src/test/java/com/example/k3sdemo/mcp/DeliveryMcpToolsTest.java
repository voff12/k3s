package com.example.k3sdemo.mcp;

import com.example.k3sdemo.delivery.dto.CreateApplicationRequest;
import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.entity.Artifact;
import com.example.k3sdemo.delivery.repository.ArtifactRepository;
import com.example.k3sdemo.delivery.service.ApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeliveryMcpTools 行为锁定：7 个工具的成功路径与错误路径（业务异常转 {success:false,error}）。
 * 不启动真实流水线（execute 依赖 K8s），只验证创建/查询/错误处理契约。
 */
@SpringBootTest
@Transactional
class DeliveryMcpToolsTest {

    @Autowired
    private DeliveryMcpTools tools;
    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ArtifactRepository artifactRepository;

    private Application onboardApp() {
        CreateApplicationRequest req = new CreateApplicationRequest();
        req.setName("订单服务");
        req.setRepoUrl("https://git.example.com/trade/order-service.git");
        req.setDefaultBranch("main");
        req.setEnvironments(List.of("BETA"));
        req.setOperatorName("吴工");
        return applicationService.create(req);
    }

    private Artifact newArtifact(Long appId) {
        Artifact a = new Artifact();
        a.setAppId(appId);
        a.setVersion("v1.0.0");
        a.setGitSha(String.format("%040d", 1));
        a.setImageRepo("harbor.local/library/order-service");
        a.setImageDigest("sha256:" + UUID.randomUUID().toString().replace("-", ""));
        return artifactRepository.save(a);
    }

    @Test
    void appCreateAndList() {
        Map<String, Object> created = tools.appCreate("订单服务", "order-service",
                "https://git.example.com/trade/order-service.git", null, "main", null);
        assertThat(created.get("success")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> appView = (Map<String, Object>) created.get("application");
        assertThat(appView.get("id")).isNotNull();

        Map<String, Object> list = tools.appList(null);
        assertThat(list.get("success")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apps = (List<Map<String, Object>>) list.get("applications");
        assertThat(apps).anyMatch(m -> "订单服务".equals(m.get("name")));
        // gitToken WRITE_ONLY：不应回显
        assertThat(apps.get(0)).doesNotContainKey("gitToken");
    }

    @Test
    void artifactListByApp() {
        Application app = onboardApp();
        Artifact a = newArtifact(app.getId());

        Map<String, Object> result = tools.artifactList(app.getId());
        assertThat(result.get("success")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) result.get("artifacts");
        assertThat(artifacts).anyMatch(m -> "v1.0.0".equals(m.get("version")));
    }

    @Test
    void releaseCreateThenStatusAndList() {
        Application app = onboardApp();
        Artifact a = newArtifact(app.getId());

        Map<String, Object> created = tools.releaseCreate(app.getId(), a.getId(), "BETA", "CANARY", "首次灰度");
        assertThat(created.get("success")).isEqualTo(true);
        Object releaseId = created.get("releaseId");
        assertThat(releaseId).isNotNull();

        Map<String, Object> status = tools.releaseStatus(((Number) releaseId).longValue());
        assertThat(status.get("success")).isEqualTo(true);
        assertThat(status.get("releaseNo")).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages = (List<Map<String, Object>>) status.get("stages");
        assertThat(stages).hasSize(6);

        Map<String, Object> list = tools.releaseList(null);
        assertThat(list.get("success")).isEqualTo(true);
    }

    @Test
    void createReleaseWithBadArtifactReturnsError() {
        Application app = onboardApp();
        Map<String, Object> result = tools.releaseCreate(app.getId(), 99999L, "BETA", "CANARY", null);
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).asString().isNotBlank();
    }

    @Test
    void releaseStatusNotFoundReturnsError() {
        Map<String, Object> result = tools.releaseStatus(99999L);
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result).containsKey("error");
    }

    @Test
    void executeDraftReleaseTriggersAsync() {
        Application app = onboardApp();
        Artifact a = newArtifact(app.getId());
        Map<String, Object> created = tools.releaseCreate(app.getId(), a.getId(), "BETA", "DEPLOY_ONLY", null);
        Long releaseId = ((Number) created.get("releaseId")).longValue();

        Map<String, Object> execute = tools.releaseExecute(releaseId);
        // execute 启动真实流水线；无 K8s 时可能标记失败，但调用契约应返回 success + releaseId
        assertThat(execute.get("success")).isEqualTo(true);
        assertThat(execute.get("releaseId")).isEqualTo(releaseId);
    }
}
