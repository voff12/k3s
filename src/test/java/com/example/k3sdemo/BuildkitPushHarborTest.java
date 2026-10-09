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
        // 同时直推 Harbor
        assertTrue(cmd.contains("--output type=image,name=\" + fullImage + \",push=true"),
                "必须同时以 type=image,push=true 推送 Harbor");
    }
}
