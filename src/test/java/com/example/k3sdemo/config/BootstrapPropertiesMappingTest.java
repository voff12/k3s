package com.example.k3sdemo.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：锁定 bootstrap.properties 与 k3s-deploy2.yaml 之间的环境变量契约。
 *
 * 背景：k3s-deploy2.yaml 曾丢失 NACOS_ENABLED=true，导致
 * spring.cloud.nacos.config.enabled 落回默认 false，整个 Nacos 客户端不激活
 * （日志零 Nacos 痕迹），数据源配置拉不到而启动失败。
 * 本测试保证两份文件的占位符映射与注入的 env 名称始终一致。
 */
class BootstrapPropertiesMappingTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z_]+):([^}]*)}");

    private final Path bootstrap = repoFile("src/main/resources/bootstrap.properties");
    private final Path deployment = repoFile("k3s-deploy2.yaml");

    private static Path repoFile(String relative) {
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd.resolve(relative);
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        // surefire 工作目录可能是模块根也可能不是，向上找 .git边界
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(".git"))) {
                return dir.resolve(relative);
            }
        }
        throw new IllegalStateException("找不到 " + relative + "（cwd=" + cwd + "）");
    }

    private Map<String, String> placeholders(Path file) throws IOException {
        return Pattern.compile("\\R")
                .splitAsStream(Files.readString(file))
                .filter(line -> !line.trim().startsWith("#"))
                .map(PLACEHOLDER::matcher)
                .<Matcher>map(m -> m)
                .flatMap(m -> {
                    java.util.List<String[]> found = new java.util.ArrayList<>();
                    while (m.find()) {
                        found.add(new String[]{m.group(1), m.group(2)});
                    }
                    return found.stream();
                })
                .collect(Collectors.toMap(a -> a[0], a -> a[1], (l, r) -> l));
    }

    @Test
    void bootstrapPlaceholdersAreInjectedByDeployment() throws IOException {
        Map<String, String> required = placeholders(bootstrap);
        String deploy = Files.readString(deployment);

        for (String envName : required.keySet()) {
            assertTrue(
                    deploy.contains("- name: " + envName + "\n"),
                    "k3s-deploy2.yaml 必须注入 " + envName
                            + "（bootstrap.properties 的占位符依赖它），当前缺失");
        }
    }

    @Test
    void nacosClientIsExplicitlyEnabledInCluster() throws IOException {
        String deploy = Files.readString(deployment);
        assertTrue(
                deploy.contains("- name: NACOS_ENABLED") && deploy.contains("value: \"true\""),
                "k3s-deploy2.yaml 必须显式设置 NACOS_ENABLED=true："
                        + "bootstrap.properties 中 spring.cloud.nacos.config.enabled 默认 false，"
                        + "缺失会导致 Nacos 客户端整体不激活（表现为日志零 Nacos 痕迹）");
    }

    @Test
    void nacosNamespaceEmptyMeansPublic() throws IOException {
        String deploy = Files.readString(deployment);
        assertTrue(
                deploy.contains("- name: NACOS_NAMESPACE\n              value: \"\""),
                "NACOS_NAMESPACE 应为空串（= public 命名空间）；"
                        + "字面量 \"public\" 不是合法 namespace ID，会导致查不到配置");
    }
}
