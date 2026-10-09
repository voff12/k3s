package com.example.k3sdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预装工具镜像回归：init 容器用的镜像 (git.image) 必须预装 git/curl，
 * 不允许退回「运行时 apk add」—— 那会让每次构建重复下载 Alpine 包（30-60s/次）。
 *
 * 配套: docker/git-alpine.Dockerfile + prewarm-images.sh 阶段1.5 构建导入。
 */
class PipelinesUsePrebakedGitImageTest {

    private static final String DEVOPS = "src/main/java/com/example/k3sdemo/service/DevOpsService.java";
    private static final String PROPS = "src/main/resources/application.properties";

    @Test
    void noRuntimeApkAddInPipelineContainers() throws IOException {
        String src = Files.readString(Path.of(DEVOPS));
        assertFalse(src.contains("apk add"),
                "init 容器命令不应再运行时 apk add —— git/curl 已预装在 git-alpine 工具镜像内");
        assertFalse(src.contains("dl-cdn.alpinelinux.org"),
                "init 容器命令不应再运行时 sed 换 apk 源（镜像构建期已完成）");
    }

    @Test
    void gitImagePointsToPrebakedToolImage() throws IOException {
        String props = Files.readString(Path.of(PROPS));
        assertTrue(props.contains("git.image=docker.io/library/git-alpine:3.19"),
                "git.image 应指向预装 git/curl 的自制工具镜像，而非裸 alpine");
    }

    @Test
    void prewarmScriptBuildsGitAlpine() throws IOException {
        String script = Files.readString(Path.of("prewarm-images.sh"));
        assertTrue(script.contains("git-alpine.Dockerfile"),
                "prewarm-images.sh 应包含 git-alpine 工具镜像的构建导入步骤");
        assertTrue(script.contains("docker.io/library/git-alpine:3.19"),
                "prewarm-images.sh 应导入 docker.io/library/git-alpine:3.19");
    }
}
