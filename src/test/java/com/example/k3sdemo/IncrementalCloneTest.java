package com.example.k3sdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量 clone 回归：两条流水线（DevOpsService / ReleaseService）的 clone 命令必须
 * ① 检测 /workspace/.git 存在则 fetch + reset --hard + git clean -fd（幂等更新）
 * ② 单分支模式用浅克隆(--depth 1)+浅增量(fetch --depth 1)，绕开 ghproxy 大包断流
 * ③ 合并模式保留全量 clone（merge 需要完整历史）
 * ④ workspace 卷挂按 gitUrl 哈希派生的 PVC，不再是 emptyDir。
 */
class IncrementalCloneTest {

    private static final String DEVOPS = "src/main/java/com/example/k3sdemo/service/DevOpsService.java";
    private static final String RELEASE = "src/main/java/com/example/k3sdemo/service/ReleaseService.java";

    private void assertIncrementalClone(String path, String name) throws IOException {
        String src = Files.readString(Path.of(path));
        assertTrue(src.contains("if [ -d /workspace/.git ]"),
                name + " clone 命令缺少已有工作区检测");
        // 单分支模式: 浅克隆 + 浅增量 (ghproxy 大包断流规避)
        assertTrue(src.contains("git clone --depth 1 --branch"),
                name + " 单分支首次克隆应用 --depth 1 浅克隆");
        assertTrue(src.contains("git fetch --depth 1 origin"),
                name + " 单分支增量路径应用 fetch --depth 1");
        assertTrue(src.contains("git reset --hard FETCH_HEAD"),
                name + " 单分支增量路径应 reset --hard 到 FETCH_HEAD");
        // 合并模式: 全量 clone + 全量 fetch (merge 需要完整历史)
        assertTrue(src.contains("git fetch --prune origin"),
                name + " 合并模式增量路径应保留全量 fetch --prune");
        assertTrue(src.contains("git reset --hard origin/"),
                name + " 合并模式增量路径应 reset --hard 到 origin/<base>");
        assertTrue(src.contains("git clean -fd"),
                name + " 增量路径缺少 git clean -fd 清理残留");
        assertTrue(src.contains("git remote set-url origin"),
                name + " 增量路径缺少 remote set-url（token/URL 变更时刷新凭据）");
    }

    @Test
    void devOpsCloneIsIncremental() throws IOException {
        assertIncrementalClone(DEVOPS, "DevOpsService");
    }

    @Test
    void releaseCloneIsIncremental() throws IOException {
        assertIncrementalClone(RELEASE, "ReleaseService");
    }

    @Test
    void devOpsWorkspaceIsPvcNotEmptyDir() throws IOException {
        String src = Files.readString(Path.of(DEVOPS));
        assertTrue(src.contains("workspacePvcName(config.getGitUrl())"),
                "DevOpsService workspace 卷应挂按 gitUrl 派生的 PVC");
        assertTrue(src.contains("ensureWorkspacePvc(client"),
                "DevOpsService 创建 Job 前应确保 workspace PVC 存在");
    }

    @Test
    void releaseWorkspaceIsPvcNotEmptyDir() throws IOException {
        String src = Files.readString(Path.of(RELEASE));
        assertTrue(src.contains("DevOpsService.workspacePvcName(config.getGitUrl())"),
                "ReleaseService workspace 卷应挂按 gitUrl 派生的 PVC");
        assertTrue(src.contains("DevOpsService.ensureWorkspacePvc(client"),
                "ReleaseService 创建 Job 前应确保 workspace PVC 存在");
        assertTrue(src.contains("maven-repo-pvc"),
                "ReleaseService maven 依赖缓存 PVC 挂载应保持不变");
    }

    @Test
    void workspacePvcNameIsDeterministicAndDnsSafe() {
        String a = com.example.k3sdemo.service.DevOpsService
                .workspacePvcName("https://git.example.com/foo/bar.git");
        String b = com.example.k3sdemo.service.DevOpsService
                .workspacePvcName("https://git.example.com/foo/bar.git");
        String c = com.example.k3sdemo.service.DevOpsService
                .workspacePvcName("https://git.example.com/other/repo.git");
        assertTrue(a.equals(b), "同一 gitUrl 应派生同一 PVC 名");
        assertFalse(a.equals(c), "不同 gitUrl 应派生不同 PVC 名");
        assertTrue(a.matches("workspace-pvc-[0-9a-f]{8}"),
                "PVC 名应为 workspace-pvc-<8位hex>，实际: " + a);
    }

    @Test
    void devOpsJavaDockerfileHasDependencyLayer() throws IOException {
        String src = Files.readString(Path.of(DEVOPS));
        assertTrue(src.contains("dependency:go-offline"),
                "DevOpsService Java Dockerfile 应包含 dependency:go-offline 依赖分层以复用 BuildKit cache");
    }
}
