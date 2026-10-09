package com.example.k3sdemo.delivery.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 读取远端 Git 仓库的提交信息（新建发布自动关联最新代码提交用）。
 * 纯 JGit 实现，运行时无需 git 二进制；与 DevOpsService.listRemoteBranches 同源。
 */
@Service
public class GitCommitService {

    @Value("${gitlab.token:}")
    private String globalGitlabToken;

    /**
     * 查指定分支的最新提交（分支头 SHA + 提交标题）。
     *
     * @param repoUrl 仓库地址 (https://...)
     * @param branch  分支名，空则用 defaultBranch 语义由调用方先解析
     * @param token   应用自配 token；为空回退全局 gitlab.token
     */
    public Map<String, String> latestCommit(String repoUrl, String branch, String token) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("repoUrl 不能为空");
        }
        String br = (branch == null || branch.isBlank()) ? "main" : branch.trim();
        String effToken = (token != null && !token.isBlank()) ? token : globalGitlabToken;
        String sha = resolveBranchHead(repoUrl, br, effToken);
        Map<String, String> result = new HashMap<>();
        result.put("gitSha", sha);
        result.put("gitBranch", br);
        result.put("commitTitle", readCommitTitle(repoUrl, sha, effToken));
        return result;
    }

    /** ls-remote 读分支头 SHA（拿不到提交标题，标题需浅 clone 另取）。 */
    private String resolveBranchHead(String repoUrl, String branch, String token) {
        try {
            var cmd = Git.lsRemoteRepository().setRemote(repoUrl).setHeads(true).setTags(false);
            if (token != null && !token.isBlank()) {
                cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider("oauth2", token));
            }
            for (var ref : cmd.call()) {
                if (("refs/heads/" + branch).equals(ref.getName())) {
                    return ref.getObjectId().getName();
                }
            }
            throw new IllegalStateException("远端不存在分支: " + branch);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("无法读取远端分支 " + branch + ": " + e.getMessage(), e);
        }
    }

    /** 浅 clone (--depth 1) 到临时目录读取该提交的标题；失败不阻断（返回 null）。 */
    private String readCommitTitle(String repoUrl, String sha, String token) {
        java.nio.file.Path tmp = null;
        try {
            tmp = java.nio.file.Files.createTempDirectory("k3s-git-");
            var cmd = Git.cloneRepository().setURI(repoUrl).setDirectory(tmp.toFile())
                    .setDepth(1).setNoTags();
            if (token != null && !token.isBlank()) {
                cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider("oauth2", token));
            }
            try (Git git = cmd.call()) {
                var commit = git.getRepository().parseCommit(
                        git.getRepository().resolve(sha));
                return commit != null ? commit.getShortMessage() : null;
            }
        } catch (Exception e) {
            return null; // 标题取不到不阻断主流程
        } finally {
            if (tmp != null) {
                deleteQuietly(tmp);
            }
        }
    }

    private void deleteQuietly(java.nio.file.Path dir) {
        try {
            java.nio.file.Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
        }
    }
}
