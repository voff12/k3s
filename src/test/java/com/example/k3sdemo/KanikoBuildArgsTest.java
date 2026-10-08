package com.example.k3sdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kaniko 构建参数回归：两条构建链路（DevOpsService 离线 Job、ReleaseService 推送 Job）
 * 都必须带低内存快照参数 --snapshot-mode=redo，避免全文件系统快照的内存峰值。
 */
class KanikoBuildArgsTest {

    @Test
    void devOpsServiceKanikoJobUsesRedoSnapshotMode() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/example/k3sdemo/service/DevOpsService.java"));
        assertTrue(src.contains("\"--snapshot-mode=redo\""),
                "DevOpsService 的 kaniko init 容器缺少 --snapshot-mode=redo");
    }

    @Test
    void releaseServiceKanikoJobUsesRedoSnapshotMode() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/example/k3sdemo/service/ReleaseService.java"));
        assertTrue(src.contains("\"--snapshot-mode=redo\""),
                "ReleaseService 的 kaniko-build init 容器缺少 --snapshot-mode=redo");
    }
}
