package com.example.k3sdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DevOps 流水线常规部署的 Service 回归：
 * deployToK3s 更新镜像后必须调 ensureService 补建 NodePort Service，
 * 否则流水线部署的应用（如 app-1）没有 Service、无法对外访问
 * （ReleaseService 常规部署与预览模式均已建 Service，唯独此路径缺失）。
 */
class DevOpsDeployEnsureServiceTest {

    private static final String DEVOPS = "src/main/java/com/example/k3sdemo/service/DevOpsService.java";

    @Test
    void deployToK3sEnsuresService() throws IOException {
        String src = Files.readString(Path.of(DEVOPS));
        assertTrue(src.contains("ensureService(client, ns, deployName, config.getEffectiveAppPort(), run)"),
                "deployToK3s 更新镜像后应调 ensureService 补建 NodePort Service");
    }

    @Test
    void ensureServiceCreatesNodePortWithAppSelector() throws IOException {
        String src = Files.readString(Path.of(DEVOPS));
        assertTrue(src.contains("private void ensureService(KubernetesClient client, String namespace, String deployName, int appPort, PipelineRun run)"),
                "DevOpsService 应有 ensureService 方法");
        assertTrue(src.contains("withType(\"NodePort\")"),
                "ensureService 应创建 NodePort 类型 Service");
        assertTrue(src.contains("addToSelector(\"app\", deployName)"),
                "ensureService 的 selector 应匹配 Deployment 的 app=<deployName> 标签");
    }
}
