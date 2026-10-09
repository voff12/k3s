package com.example.k3sdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BuildKit 容器内存限制回归：两条构建链路（DevOpsService 离线 Job、ReleaseService 推送 Job）
 * 的 buildkit 容器内存 limit 必须走 buildkit.memory-limit 配置（@Value，默认 4Gi），
 * 不允许再出现 "4Gi" 硬编码（改限制需重新编译的问题已修复）。
 */
class BuildkitMemoryLimitTest {

    private static final String DEVOPS = "src/main/java/com/example/k3sdemo/service/DevOpsService.java";
    private static final String RELEASE = "src/main/java/com/example/k3sdemo/service/ReleaseService.java";

    @Test
    void buildkitMemoryLimitIsConfigurable() throws IOException {
        for (String path : new String[]{DEVOPS, RELEASE}) {
            String src = Files.readString(Path.of(path));
            assertTrue(src.contains("${buildkit.memory-limit:4Gi}"),
                    path + " 缺少 buildkit.memory-limit @Value 配置");
            assertTrue(src.contains("new Quantity(buildkitMemoryLimit)"),
                    path + " buildkit 容器内存 limit 应引用配置字段");
            assertFalse(src.contains("new Quantity(\"4Gi\")"),
                    path + " 不应再硬编码 4Gi 内存 limit");
        }
    }
}
