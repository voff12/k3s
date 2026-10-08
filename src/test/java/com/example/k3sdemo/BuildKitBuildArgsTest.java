package com.example.k3sdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BuildKit 构建参数回归：两条构建链路（DevOpsService 离线 Job、ReleaseService 推送 Job）
 * 都必须使用 BuildKit daemonless（buildctl-daemonless.sh）+ rootless 安全上下文，
 * 且不再引用 Kaniko executor 参数。
 */
class BuildKitBuildArgsTest {

    private static final String DEVOPS = "src/main/java/com/example/k3sdemo/service/DevOpsService.java";
    private static final String RELEASE = "src/main/java/com/example/k3sdemo/service/ReleaseService.java";

    @Test
    void devOpsServiceUsesBuildKitDaemonless() throws IOException {
        String src = Files.readString(Path.of(DEVOPS));
        assertTrue(src.contains("buildctl-daemonless.sh build"),
                "DevOpsService 构建容器缺少 buildctl-daemonless.sh 调用");
        assertTrue(src.contains("type=docker"),
                "DevOpsService 离线链路必须输出 docker 格式 tar 供 ctr import");
        assertTrue(src.contains("withRunAsUser(1000L)"),
                "BuildKit rootless 需要 runAsUser=1000");
        assertTrue(src.contains("--oci-worker-no-process-sandbox"),
                "K8s 环境 rootless BuildKit 需要 BUILDKITD_FLAGS=--oci-worker-no-process-sandbox");
        assertTrue(src.contains("type=registry"),
                "构建缓存应使用 registry cache 复用 Harbor");
        assertFalse(src.contains("--snapshot-mode"),
                "Kaniko 专有参数 --snapshot-mode 应已移除");
        assertFalse(src.contains("\"kaniko\""),
                "容器名 kaniko 应已改为 buildkit");
    }

    @Test
    void releaseServiceUsesBuildKitDaemonless() throws IOException {
        String src = Files.readString(Path.of(RELEASE));
        assertTrue(src.contains("buildctl-daemonless.sh build"),
                "ReleaseService 构建容器缺少 buildctl-daemonless.sh 调用");
        assertTrue(src.contains("type=image"),
                "ReleaseService 链路必须 --output type=image,push=true 推 Harbor");
        assertTrue(src.contains("push=true"),
                "ReleaseService 链路必须推送镜像到 Harbor");
        assertTrue(src.contains("withRunAsUser(1000L)"),
                "BuildKit rootless 需要 runAsUser=1000");
        assertTrue(src.contains("--oci-worker-no-process-sandbox"),
                "K8s 环境 rootless BuildKit 需要 BUILDKITD_FLAGS=--oci-worker-no-process-sandbox");
        assertTrue(src.contains("type=registry"),
                "构建缓存应使用 registry cache 复用 Harbor");
        assertFalse(src.contains("--snapshot-mode"),
                "Kaniko 专有参数 --snapshot-mode 应已移除");
        assertFalse(src.contains("/kaniko/executor"),
                "Kaniko executor 入口应已移除");
    }
}
