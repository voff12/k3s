package com.example.k3sdemo.delivery;

import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.entity.Artifact;
import com.example.k3sdemo.delivery.service.GitCommitService;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新建发布自动关联最新提交 + 制品登记自动补齐 的聚焦测试。
 * 用本地 git 仓库（file://）验证 JGit 读远端分支头 SHA 与提交标题；
 * 不依赖外部网络，file:// 协议下不走 token。
 */
class ArtifactAutoRegisterTest {

    private final GitCommitService gitCommitService = new GitCommitService();

    @TempDir
    Path tmp;

    /** 建一个本地裸仓可读的 git 仓库，含两次提交，返回其 file:// 地址与最新提交信息。 */
    private String initRepoWithTwoCommits(String[] holder) throws Exception {
        Path repo = tmp.resolve("repo");
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            Files.writeString(repo.resolve("f.txt"), "v1");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("first commit").call();
            Files.writeString(repo.resolve("f.txt"), "v2");
            git.add().addFilepattern(".").call();
            var rev = git.commit().setMessage("feat: second commit").call();
            holder[0] = rev.getName();           // 完整 40 位 SHA
            holder[1] = rev.getShortMessage();   // 提交标题
        }
        return repo.toUri().toString();
    }

    @Test
    void latestCommit_readsBranchHeadShaAndTitle() throws Exception {
        String[] holder = new String[2];
        String url = initRepoWithTwoCommits(holder);

        java.util.Map<String, String> lc = gitCommitService.latestCommit(url, "main", null);

        assertThat(lc.get("gitSha")).isEqualTo(holder[0]);
        assertThat(lc.get("gitBranch")).isEqualTo("main");
        assertThat(lc.get("commitTitle")).isEqualTo(holder[1]);
    }

    @Test
    void latestCommit_missingBranch_throws() throws Exception {
        String[] holder = new String[2];
        String url = initRepoWithTwoCommits(holder);

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> gitCommitService.latestCommit(url, "no-such-branch", null));
    }

    /** imageRepo 生成规则与 imageDigest 合成（DeliveryQueryController 内联逻辑的镜像）。 */
    @Test
    void imageRepoAndDigest_derivation() {
        Application app = new Application();
        app.setCode("order-service");
        Artifact artifact = new Artifact();
        artifact.setGitSha("abc123");

        // 与 DeliveryQueryController 一致的规则
        String imageRepo = "harbor.local/library/" + app.getCode();
        artifact.setImageRepo(imageRepo);
        artifact.setImageDigest("sha256:" + sha256Hex(
                imageRepo + ":" + "v1" + "@" + artifact.getGitSha()));

        assertThat(artifact.getImageRepo()).isEqualTo("harbor.local/library/order-service");
        assertThat(artifact.getImageDigest()).startsWith("sha256:").hasSize("sha256:".length() + 64);
    }

    private String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
