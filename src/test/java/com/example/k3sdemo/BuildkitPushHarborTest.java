package com.example.k3sdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 镜像分发（方案 A）：buildkit 容器在产出 docker tar 供节点导入之外，
 * 必须同时以 type=image push=true 把镜像推到 Harbor——修复"新 Pod 调度到
 * 别的节点本地无镜像、回源 Harbor 又没有"导致的 ImagePullBackOff。
 */
class BuildkitPushHarborTest {

    private static final String SRC = "src/main/java/com/example/k3sdemo/service/DevOpsService.java";

    @Test
    void buildkitCmd_hasDualOutput_tarImportAndHarborPush() throws IOException {
        String src = Files.readString(Path.of(SRC));
        int start = src.indexOf("String buildkitCmd = ");
        int end = src.indexOf("\" --progress=plain\"", start);
        assertTrue(start > 0 && end > start, "必须能定位 buildkitCmd 构造段");
        String cmd = src.substring(start, end);
        // 离线导入路径保留
        assertTrue(cmd.contains("--output type=docker,name=\" + fullImage + \",dest=/workspace/image.tar"),
                "必须保留 docker tar 输出供 loader 导入节点 containerd");
        // 同时直推 Harbor (push 参数与压缩参数在同一字符串, 详见 zstd 测试)
        assertTrue(cmd.contains("--output type=image,name=\" + fullImage"),
                "必须同时以 type=image 推送 Harbor");
        assertTrue(cmd.contains("push=true"),
                "推送 Harbor 必须带 push=true");
    }

    @Test
    void buildkitPush_usesZstdCompression_bothPipelines() throws IOException {
        // zstd 三件套缺一不可: oci-mediatypes=true 才支持 zstd(type=image 默认 docker schema2 会忽略
        // compression 参数, moby/buildkit#5458); force-compression=true 强制重压缓存里的 gzip 层
        String zstdParams = ",push=true,oci-mediatypes=true,compression=zstd,compression-level=3,force-compression=true";
        String devops = Files.readString(Path.of("src/main/java/com/example/k3sdemo/service/DevOpsService.java"));
        assertTrue(devops.contains(zstdParams), "DevOpsService 推 Harbor 必须启用 zstd 压缩");
        String release = Files.readString(Path.of("src/main/java/com/example/k3sdemo/service/ReleaseService.java"));
        assertTrue(release.contains(zstdParams), "ReleaseService 推 Harbor 必须启用 zstd 压缩");
    }

    @Test
    void cacheExport_zstdAndReleaseMinMode() throws IOException {
        // A: 两条流水线的缓存导出都启用 zstd(与镜像输出同参数, 上传提速)
        String cacheZstd = "compression=zstd,compression-level=3,force-compression=true";
        String devops = Files.readString(Path.of("src/main/java/com/example/k3sdemo/service/DevOpsService.java"));
        int devExport = devops.indexOf("--export-cache type=registry,ref=");
        assertTrue(devExport > 0, "DevOpsService 必须有 registry 缓存导出");
        assertTrue(devops.substring(devExport, devops.indexOf("\n", devExport)).contains(cacheZstd),
                "DevOpsService 缓存导出必须启用 zstd");
        String release = Files.readString(Path.of("src/main/java/com/example/k3sdemo/service/ReleaseService.java"));
        int relExport = release.indexOf("--export-cache type=registry,ref=");
        assertTrue(relExport > 0, "ReleaseService 必须有 registry 缓存导出");
        assertTrue(release.substring(relExport, release.indexOf("\n", relExport)).contains(cacheZstd),
                "ReleaseService 缓存导出必须启用 zstd");
        // B: Release 流水线 Dockerfile 是单阶段 runtime-only, mode=max 会把镜像本体再推一遍 → 降为 min
        String relExportLine = release.substring(relExport, release.indexOf("\n", relExport) + 1);
        assertTrue(relExportLine.contains("mode=min"),
                "ReleaseService 缓存导出应为 mode=min(单阶段镜像, max 等于重复推镜像)");
    }
}
