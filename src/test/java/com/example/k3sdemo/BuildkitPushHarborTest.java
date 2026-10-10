package com.example.k3sdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 镜像产出路径守卫：
 * - DevOps 流水线：仅产出 docker tar 供 loader ctr import 到节点 containerd，
 *   不再直推 Harbor——节点 /etc/hosts 变更后 harbor.local 落到 443 被 Traefik
 *   默认证书拦截(x509)，直推段已移除以彻底绕开 Harbor 的 HTTPS 推送通道。
 * - Release 流水线：无 loader/tar 路径，仍依赖 type=image push=true 直推 Harbor，
 *   应用 Pod 用 harbor-registry-secret 回源拉取。
 */
class BuildkitPushHarborTest {

    private static final String SRC = "src/main/java/com/example/k3sdemo/service/DevOpsService.java";

    @Test
    void devopsBuildkitCmd_tarOnlyNoHarborPush() throws IOException {
        String src = Files.readString(Path.of(SRC));
        int start = src.indexOf("String buildkitCmd = ");
        int end = src.indexOf("--progress=plain", start);
        assertTrue(start > 0 && end > start, "必须能定位 buildkitCmd 构造段");
        String cmd = src.substring(start, end);
        // 离线导入路径保留
        assertTrue(cmd.contains("--output type=docker,name=\" + fullImage + \",dest=/workspace/image.tar"),
                "必须保留 docker tar 输出供 loader 导入节点 containerd");
        // 直推 Harbor 段已移除: x509 修复后改走 tarball + ctr import, 绕开 Harbor HTTPS 推送通道
        assertTrue(!cmd.contains("\" --output type=image"),
                "DevOps 流水线不得再直推 Harbor(harbor.local 落 443 被 Traefik 拦截致 x509)");
        assertTrue(!cmd.contains(",push=true"),
                "DevOps 流水线不得再带 push=true 推送标志");
    }

    @Test
    void devopsPushRemoved_releasePushKeepsZstd() throws IOException {
        // DevOps: 直推已删, 不得再出现 zstd 推送三件套
        String zstdParams = ",push=true,oci-mediatypes=true,compression=zstd,compression-level=3,force-compression=true";
        String devops = Files.readString(Path.of("src/main/java/com/example/k3sdemo/service/DevOpsService.java"));
        assertTrue(!devops.contains(zstdParams), "DevOpsService 直推段已移除, 不应再含 zstd 推送参数");
        // Release: 无 loader/tar 路径, 仍靠直推 Harbor; zstd 三件套缺一不可
        // (oci-mediatypes=true 才支持 zstd, force-compression=true 强制重压缓存里的 gzip 层)
        String release = Files.readString(Path.of("src/main/java/com/example/k3sdemo/service/ReleaseService.java"));
        assertTrue(release.contains(zstdParams), "ReleaseService 推 Harbor 必须保留 zstd 压缩");
    }

    @Test
    void cacheExport_disabledButImportKept() throws IOException {
        // 远程缓存导出每次耗时 ~17 分钟(占构建总耗时 97%), 而构建机 BuildKit 状态已由
        // buildkit-cache-pvc 持久化 → 两条流水线都不再导出; import-cache 保留以便 PVC 重建后恢复
        String devops = Files.readString(Path.of("src/main/java/com/example/k3sdemo/service/DevOpsService.java"));
        assertTrue(!devops.contains("--export-cache"), "DevOpsService 不得再导出远程缓存(导出耗时占 97%)");
        assertTrue(devops.contains("--import-cache type=registry,ref="),
                "DevOpsService 必须保留 import-cache 以便 PVC 重建后从 Harbor 恢复缓存");
        String release = Files.readString(Path.of("src/main/java/com/example/k3sdemo/service/ReleaseService.java"));
        assertTrue(!release.contains("--export-cache"), "ReleaseService 不得再导出远程缓存(导出耗时占 97%)");
        assertTrue(release.contains("--import-cache type=registry,ref="),
                "ReleaseService 必须保留 import-cache 以便 PVC 重建后从 Harbor 恢复缓存");
    }
}
