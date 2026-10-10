package com.example.k3sdemo.delivery;

import com.example.k3sdemo.delivery.service.GitCommitService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按仓库平台选择 JGit Basic Auth 用户名的行为锁定：
 * GitHub PAT 用 x-access-token，GitLab/其他用 oauth2。
 * 修复前用户名写死 oauth2，私有 GitHub 仓库 401 → 控制器 500。
 */
class GitCommitServiceTest {

    @Test
    void githubUrlUsesXAccessToken() {
        assertThat(GitCommitService.authUsernameFor("https://github.com/acme/order-service.git"))
                .isEqualTo("x-access-token");
    }

    @Test
    void gitlabUrlKeepsOauth2() {
        assertThat(GitCommitService.authUsernameFor("https://gitlab.com/acme/order-service.git"))
                .isEqualTo("oauth2");
    }

    @Test
    void selfHostedGitlabKeepsOauth2() {
        assertThat(GitCommitService.authUsernameFor("https://git.internal.local/team/repo.git"))
                .isEqualTo("oauth2");
    }

    @Test
    void nullUrlFallsBackToOauth2() {
        assertThat(GitCommitService.authUsernameFor(null)).isEqualTo("oauth2");
    }
}
