package com.example.k3sdemo.delivery;

import com.example.k3sdemo.delivery.dto.CreateApplicationRequest;
import com.example.k3sdemo.delivery.dto.UpdateApplicationRequest;
import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.service.ApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用编辑（PUT /applications/{id}）行为锁定：
 * - 部分更新仓库连接配置；gitToken 三态（不传=不变 / 空串=清除 / 非空=替换）
 * - gitToken 序列化不回显明文（WRITE_ONLY），修复"注释声称不回显但实际泄漏"
 */
@SpringBootTest
@Transactional
class ApplicationServiceTest {

    @Autowired
    private ApplicationService applicationService;

    private Application onboardApp() {
        CreateApplicationRequest req = new CreateApplicationRequest();
        req.setName("订单服务");
        req.setRepoUrl("https://git.example.com/trade/order-service.git");
        req.setDefaultBranch("main");
        req.setEnvironments(List.of("BETA"));
        req.setOperatorName("吴工");
        return applicationService.create(req);
    }

    @Test
    void updateGitTokenReplacesValue() {
        Application app = onboardApp();
        UpdateApplicationRequest req = new UpdateApplicationRequest();
        req.setGitToken("ghp_test123");
        req.setOperatorName("吴工");
        Application updated = applicationService.update(app.getId(), req);
        assertThat(updated.getGitToken()).isEqualTo("ghp_test123");
        assertThat(updated.getRepoUrl()).isEqualTo("https://git.example.com/trade/order-service.git");
    }

    @Test
    void updateWithNullGitTokenKeepsExisting() {
        Application app = onboardApp();
        UpdateApplicationRequest set = new UpdateApplicationRequest();
        set.setGitToken("ghp_test123");
        applicationService.update(app.getId(), set);

        UpdateApplicationRequest noop = new UpdateApplicationRequest();
        noop.setDefaultBranch("develop");
        Application updated = applicationService.update(app.getId(), noop);
        assertThat(updated.getGitToken()).isEqualTo("ghp_test123"); // 未传 token → 保留
        assertThat(updated.getDefaultBranch()).isEqualTo("develop");
    }

    @Test
    void updateWithBlankGitTokenClears() {
        Application app = onboardApp();
        UpdateApplicationRequest set = new UpdateApplicationRequest();
        set.setGitToken("ghp_test123");
        applicationService.update(app.getId(), set);

        UpdateApplicationRequest clear = new UpdateApplicationRequest();
        clear.setGitToken("  ");
        Application updated = applicationService.update(app.getId(), clear);
        assertThat(updated.getGitToken()).isNull();
    }

    @Test
    void gitTokenNotSerializedInJson() throws Exception {
        Application app = new Application();
        app.setGitToken("ghp_secret");
        String json = new ObjectMapper().writeValueAsString(app);
        assertThat(json).doesNotContain("ghp_secret");
        assertThat(json).doesNotContain("gitToken");
    }
}
