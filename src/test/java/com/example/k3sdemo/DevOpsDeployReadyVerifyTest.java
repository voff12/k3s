package com.example.k3sdemo;

import com.example.k3sdemo.model.PipelineConfig;
import com.example.k3sdemo.model.PipelineRun;
import com.example.k3sdemo.service.DevOpsService;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水线部署确认（方案 B）：deployToK3s 更新 Deployment 后必须等待副本 Ready
 * （180s 超时），未就绪时流水线判 FAILED——修复"Pod 拉镜像卡死也报流水线成功"。
 * 与 ReleaseService.DeploymentReadyVerifyTest 对齐。
 */
class DevOpsDeployReadyVerifyTest {

    private static final String SRC = "src/main/java/com/example/k3sdemo/service/DevOpsService.java";

    private Deployment deploymentWith(Integer desired, Integer ready) {
        return new DeploymentBuilder()
                .withNewSpec().withReplicas(desired).endSpec()
                .withNewStatus()
                .withReadyReplicas(ready == null ? 0 : ready)
                .endStatus()
                .build();
    }

    private boolean invokeIsReady(Deployment d) throws Exception {
        Method m = DevOpsService.class.getDeclaredMethod("isDeploymentReady", Deployment.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, d);
    }

    @Test
    void isDeploymentReady_matchesReadyToDesired() throws Exception {
        assertTrue(invokeIsReady(deploymentWith(1, 1)), "1/1 应判定 Ready");
        assertFalse(invokeIsReady(deploymentWith(1, 0)), "0/1 不应判定 Ready");
        assertFalse(invokeIsReady(deploymentWith(3, 2)), "2/3 不应判定 Ready");
        assertTrue(invokeIsReady(deploymentWith(null, 1)), "desired 缺省按 1, ready=1 应 Ready");
        assertFalse(invokeIsReady(null), "Deployment 为 null 不应判定 Ready");
    }

    @Test
    void waitForDeploymentReady_timeoutFailsRunWithReason() throws Exception {
        DevOpsService service = new DevOpsService();
        PipelineRun run = new PipelineRun(new PipelineConfig());
        Method m = DevOpsService.class.getDeclaredMethod("waitForDeploymentReady",
                io.fabric8.kubernetes.client.KubernetesClient.class, String.class, String.class,
                long.class, PipelineRun.class);
        m.setAccessible(true);
        // client 为 null → 第一次取 Deployment 即视为不可达，按超时/异常路径返回 false
        boolean ok = (boolean) m.invoke(service, null, "default", "app", 0L, run);
        assertFalse(ok, "Deployment 取不到时必须返回 false");
        List<String> logs = run.getLogs();
        assertTrue(logs.stream().anyMatch(l -> l.contains("副本未就绪") || l.contains("就绪确认失败")),
                "超时/取不到时必须留下带原因的日志, 实际: " + logs);
    }

    @Test
    void source_deployToK3sWaitsForReadyAndNoBlindSleep() throws IOException {
        String src = Files.readString(Path.of(SRC));
        assertTrue(src.contains("private boolean deployToK3s("),
                "deployToK3s 必须返回 boolean 供主流程判定成败");
        assertTrue(src.contains("private boolean waitForDeploymentReady("),
                "必须存在 waitForDeploymentReady 轮询方法");
        assertTrue(src.contains("static boolean isDeploymentReady("),
                "必须有可单测的 static isDeploymentReady 纯函数");
        int start = src.indexOf("private boolean deployToK3s(");
        int end = src.indexOf("private void ensureService(", start);
        if (end < 0) {
            end = src.indexOf("ensureService(KubernetesClient", start);
        }
        assertTrue(end > start, "必须能定位 deployToK3s 方法体");
        String body = src.substring(start, end);
        assertTrue(body.contains("waitForDeploymentReady("), "deployToK3s 必须等待 Ready");
        assertFalse(body.contains("Thread.sleep(3000)"), "deployToK3s 不应再用盲等 3 秒");
    }

    @Test
    void source_deployFailureFailsPipelineNotSuccess() throws IOException {
        String src = Files.readString(Path.of(SRC));
        // 主流程: deployToK3s 返回 false 必须 run.fail 并 return, 不得落入 SUCCESS
        int deployCall = src.indexOf("deployToK3s(client, config, fullImage, run);");
        int successMark = src.indexOf("run.advanceTo(PipelineRun.Status.SUCCESS)");
        assertTrue(deployCall > 0 && successMark > deployCall, "必须能定位部署调用与 SUCCESS 判定");
        String block = src.substring(deployCall, successMark);
        assertTrue(block.contains("run.fail("), "部署失败分支必须 run.fail");
    }
}
