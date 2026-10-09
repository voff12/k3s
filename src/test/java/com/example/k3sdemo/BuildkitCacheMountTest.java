package com.example.k3sdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maven 依赖缓存（方案 A）：buildJavaDockerfile 生成的 Dockerfile 用
 * BuildKit cache mount 持久化 /root/.m2，使离线 daemonless 模式下
 * 依赖跨构建复用（数据落在 buildkitd 状态目录 → buildkit-cache-pvc）。
 */
class BuildkitCacheMountTest {

    private static final String SRC = "src/main/java/com/example/k3sdemo/service/DevOpsService.java";

    /** 截取 buildJavaDockerfile 方法体。 */
    private String javaDockerfileBody() throws IOException {
        String src = Files.readString(Path.of(SRC));
        int start = src.indexOf("buildJavaDockerfile(String");
        int end = src.indexOf("buildPythonDockerfile(String", start);
        return src.substring(start, end);
    }

    @Test
    void bothMvnRuns_useCacheMountForM2() throws IOException {
        String body = javaDockerfileBody();
        // dependency:go-offline 与 buildCmd 两个 RUN 都必须挂 cache mount
        assertTrue(body.contains("RUN --mount=type=cache,target=/root/.m2 mvn -s /tmp/m2/settings.xml -q dependency:go-offline"),
                "go-offline 步骤必须用 cache mount 挂 /root/.m2");
        assertTrue(body.contains("RUN --mount=type=cache,target=/root/.m2 mvn -s /tmp/m2/settings.xml \" + buildCmd"),
                "buildCmd 步骤必须用 cache mount 挂 /root/.m2");
    }

    @Test
    void settingsXml_notWrittenIntoCacheMount() throws IOException {
        String body = javaDockerfileBody();
        // settings.xml 若写进 /root/.m2 会被 cache mount 覆盖导致 401, 必须写到 /tmp 并用 -s 引用
        assertTrue(body.contains("echo '\" + settings + \"' > /tmp/m2/settings.xml"),
                "settings.xml 必须写到 /tmp/m2（不能进 /root/.m2，否则被 cache mount 覆盖）");
        assertFalse(body.contains("> /root/.m2/settings.xml"),
                "settings.xml 不应写入 /root/.m2（会被 cache mount 遮蔽）");
    }
}
