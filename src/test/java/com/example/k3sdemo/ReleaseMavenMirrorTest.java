package com.example.k3sdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maven 镜像源回归：ReleaseService 构建容器跑 mvn 前必须配置阿里云镜像源
 * (对齐 DevOpsService 的做法), 否则直连 Maven Central 在国内网络下极慢。
 * settings.xml 写到 /tmp (maven-repo-pvc 挂载 /root/.m2, 不可放 settings.xml 覆盖缓存),
 * mvn 通过 -s 引用。
 */
class ReleaseMavenMirrorTest {

    private static final String RELEASE = "src/main/java/com/example/k3sdemo/service/ReleaseService.java";

    @Test
    void releaseBuildUsesAliyunMirror() throws IOException {
        String src = Files.readString(Path.of(RELEASE));
        assertTrue(src.contains("maven.aliyun.com/repository/public"),
                "ReleaseService 构建容器缺少阿里云 Maven 镜像源配置");
        assertTrue(src.contains("settings.xml"),
                "ReleaseService 应生成 settings.xml 并由 mvn -s 引用");
    }

    @Test
    void defaultBuildCommandReferencesSettings() throws IOException {
        String src = Files.readString(Path.of(RELEASE));
        // mvn 开头且未自带 -s 的构建命令(含 UI 默认值经 ReleaseConfig.buildCommand 传入)统一注入镜像源
        assertTrue(src.contains("mvn -s /tmp/m2/settings.xml"),
                "Maven 构建命令应通过 -s 使用阿里云镜像源");
        assertTrue(src.contains("rawBuildCmd.startsWith(\"mvn\")"),
                "用户自定义 mvn 命令(含 UI 默认值)也应被注入 -s, 否则常规发布路径不走镜像源");
    }
}
