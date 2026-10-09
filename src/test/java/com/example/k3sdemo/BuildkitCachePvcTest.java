package com.example.k3sdemo;

import com.example.k3sdemo.service.DevOpsService;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * buildkitd 状态缓存 PVC：把 buildkit-state 卷从 emptyDir 改为固定 PVC，
 * 使基础镜像层跨 Job 复用，避免每次构建重拉 480MB 基础镜像。
 */
class BuildkitCachePvcTest {

    private static final String SRC = "src/main/java/com/example/k3sdemo/service/DevOpsService.java";
    private static final String RELEASE_SRC = "src/main/java/com/example/k3sdemo/service/ReleaseService.java";

    @Test
    void buildBuildkitCachePvc_specIsCorrect() {
        PersistentVolumeClaim pvc = DevOpsService.buildBuildkitCachePvc();
        assertEquals(DevOpsService.BUILDKIT_CACHE_PVC_NAME, pvc.getMetadata().getName());
        assertEquals("default", pvc.getMetadata().getNamespace());
        assertEquals(java.util.List.of("ReadWriteOnce"), pvc.getSpec().getAccessModes());
        assertEquals("10Gi", pvc.getSpec().getResources().getRequests().get("storage").toString());
    }

    @Test
    void buildkitJob_mountsCachePvcNotEmptyDir() throws IOException {
        String src = Files.readString(Path.of(SRC));
        // buildkit-state 卷必须挂 PVC（跨 Job 持久化），不能用 emptyDir（Pod 销毁即丢失）
        int volStart = src.indexOf(".withName(\"buildkit-state\")", src.indexOf("// Volumes"));
        int volEnd = src.indexOf(".endVolume()", volStart);
        String volume = src.substring(volStart, volEnd);
        assertTrue(volume.contains(".withNewPersistentVolumeClaim()"),
                "buildkit-state 卷必须是 PVC 才能跨 Job 保留基础镜像层缓存");
        assertTrue(volume.contains(".withClaimName(BUILDKIT_CACHE_PVC_NAME)"),
                "buildkit-state 卷必须引用 BUILDKIT_CACHE_PVC_NAME 常量");
        assertFalse(volume.contains("EmptyDir"),
                "buildkit-state 用 emptyDir 会在每个新 Job 清空缓存, 基础镜像仍每次重拉");
    }

    @Test
    void executePipeline_ensuresCachePvcBeforeJobCreate() throws IOException {
        String src = Files.readString(Path.of(SRC));
        // 创建 Job 前必须确保缓存 PVC 存在, 否则 Pod 卡 FailedScheduling
        assertTrue(src.contains("ensureBuildkitCachePvc(client);"),
                "executePipeline 必须在创建 buildkit Job 前调用 ensureBuildkitCachePvc");
    }

    @Test
    void releaseService_mountsSameCachePvcInBuildkitBuildContainer() throws IOException {
        String src = Files.readString(Path.of(RELEASE_SRC));
        // buildkit-state 卷必须引用与 DevOpsService 相同的共享缓存 PVC
        int volStart = src.indexOf(".withName(\"buildkit-state\")", src.indexOf("// ===== Volumes ====="));
        assertTrue(volStart > 0, "ReleaseService Volumes 段必须有 buildkit-state 卷");
        String volume = src.substring(volStart, src.indexOf(".endVolume()", volStart));
        assertTrue(volume.contains(".withNewPersistentVolumeClaim()"),
                "buildkit-state 卷必须是 PVC 才能跨 Job 保留基础镜像层缓存");
        assertTrue(volume.contains(".withClaimName(DevOpsService.BUILDKIT_CACHE_PVC_NAME)"),
                "buildkit-state 卷必须引用 DevOpsService.BUILDKIT_CACHE_PVC_NAME 共享常量");
        assertFalse(volume.contains("EmptyDir"),
                "buildkit-state 用 emptyDir 会在每个新 Job 清空缓存, 基础镜像仍每次重拉");

        // buildkit-build init 容器必须把该卷挂到 rootless 镜像的 buildkitd 状态目录
        int ctrStart = src.indexOf(".withName(\"buildkit-build\")");
        int ctrEnd = src.indexOf(".endInitContainer()", ctrStart);
        String container = src.substring(ctrStart, ctrEnd);
        assertTrue(container.contains(".withName(\"buildkit-state\")"),
                "buildkit-build 容器必须挂载 buildkit-state 卷");
        assertTrue(container.contains(".withMountPath(\"/home/user/.local/share/buildkit\")"),
                "rootless buildkit 镜像 $HOME=/home/user, 挂载路径必须是 /home/user/.local/share/buildkit");
    }

    @Test
    void releaseService_logsCacheHitAndEnsuresPvc() throws IOException {
        String src = Files.readString(Path.of(RELEASE_SRC));
        // 构建命令必须探测缓存目录并输出命中/首次日志, 便于确认缓存生效
        assertTrue(src.contains("$HOME/.local/share/buildkit/blobs"),
                "buildkit-build 命令必须探测缓存目录 blobs 是否已存在");
        assertTrue(src.contains("基础镜像层将复用"),
                "缓存命中时必须输出复用日志");
        assertTrue(src.contains("首次构建将拉取基础镜像层"),
                "缓存为空时必须输出首次构建日志");
        // executeRelease 创建 Job 前必须确保缓存 PVC 存在 (含 409 清理重建路径)
        assertTrue(src.contains("DevOpsService.ensureBuildkitCachePvc(client);"),
                "executeRelease 必须在创建 release Job 前调用 ensureBuildkitCachePvc");
    }
}
