package com.example.k3sdemo;

import com.example.k3sdemo.model.ReleaseConfig;
import com.example.k3sdemo.model.ReleaseRecord;
import com.example.k3sdemo.service.ReleaseService;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布部署确认（方案 A）：Deployment 创建/更新后必须等待副本 Ready（180s 超时），
 * 超时或部署异常时发布记录置 FAILED——修复"Pod 没起来也报发布成功"的问题。
 */
class DeploymentReadyVerifyTest {

    private static final String SRC = "src/main/java/com/example/k3sdemo/service/ReleaseService.java";

    /** 构造指定 desired/ready 副本数的 Deployment 模型。 */
    private Deployment deploymentWith(Integer desired, Integer ready) {
        return new DeploymentBuilder()
                .withNewSpec().withReplicas(desired).endSpec()
                .withNewStatus()
                .withReadyReplicas(ready == null ? 0 : ready)
                .endStatus()
                .build();
    }

    /** 调 package-private 纯函数 isDeploymentReady(Deployment)。 */
    private boolean invokeIsReady(Deployment d) throws Exception {
        Method m = ReleaseService.class.getDeclaredMethod("isDeploymentReady", Deployment.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, d);
    }

    @Test
    void isDeploymentReady_matchesReadyToDesired() throws Exception {
        assertTrue(invokeIsReady(deploymentWith(1, 1)), "1/1 应判定 Ready");
        assertTrue(invokeIsReady(deploymentWith(3, 3)), "3/3 应判定 Ready");
        assertFalse(invokeIsReady(deploymentWith(1, 0)), "0/1 不应判定 Ready");
        assertFalse(invokeIsReady(deploymentWith(3, 2)), "2/3 不应判定 Ready");
        assertTrue(invokeIsReady(deploymentWith(null, 1)), "desired 缺省按 1, ready=1 应 Ready");
        assertFalse(invokeIsReady(deploymentWith(null, 0)), "desired 缺省按 1, ready=0 不应 Ready");
    }

    @Test
    void isDeploymentReady_nullDeploymentIsNotReady() throws Exception {
        assertFalse(invokeIsReady(null), "Deployment 为 null（被删除等）不应判定 Ready");
    }

    @Test
    void waitForDeploymentReady_timeoutFailsRecordWithReason() throws Exception {
        ReleaseService service = new ReleaseService();
        ReleaseRecord record = new ReleaseRecord(new ReleaseConfig());
        Method m = ReleaseService.class.getDeclaredMethod("waitForDeploymentReady",
                io.fabric8.kubernetes.client.KubernetesClient.class, String.class, String.class,
                long.class, ReleaseRecord.class);
        m.setAccessible(true);
        // client 为 null → 第一次取 Deployment 即视为不可达，循环内按异常处理跳出并返回 false
        boolean ok = (boolean) m.invoke(service, null, "default", "app", 0L, record);
        assertFalse(ok, "Deployment 取不到时必须返回 false");
        List<String> logs = record.getLogs();
        assertTrue(logs.stream().anyMatch(l -> l.contains("副本未就绪") || l.contains("就绪确认失败")),
                "超时/取不到时必须留下带原因的日志, 实际: " + logs);
    }

    @Test
    void source_deployPathsWaitForReadyAndNoBlindSleep() throws IOException {
        String src = Files.readString(Path.of(SRC));
        assertTrue(src.contains("private boolean waitForDeploymentReady("),
                "必须存在 waitForDeploymentReady 轮询方法");
        assertTrue(src.contains("static boolean isDeploymentReady("),
                "必须有可单测的 static isDeploymentReady 纯函数");
        // 三处部署路径都必须接入等待: deployToK3s 新建/更新共用一个调用点, preview 一个
        int startK3s = src.indexOf("private boolean deployToK3s(");
        int endK3s = src.indexOf("private boolean deployToPreview(", startK3s);
        String k3sBody = src.substring(startK3s, endK3s);
        assertTrue(k3sBody.contains("waitForDeploymentReady("), "deployToK3s 必须等待 Ready");
        assertFalse(k3sBody.contains("Thread.sleep(3000)"), "deployToK3s 不应再用盲等 3 秒");
        String previewBody = src.substring(endK3s, src.indexOf("private void parseMergeResults(", endK3s));
        assertTrue(previewBody.contains("waitForDeploymentReady("), "deployToPreview 必须等待 Ready");
        assertFalse(previewBody.contains("Thread.sleep(3000)"), "deployToPreview 不应再用盲等 3 秒");
    }

    @Test
    void source_deployFailureFailsReleaseNotSuccess() throws IOException {
        String src = Files.readString(Path.of(SRC));
        // 主流程: 部署返回 false 必须 record.fail 并 return, 不得落入 advanceTo(SUCCESS)
        int deployCall = src.indexOf("deployToPreview(client, config, fullImage, record);");
        int successMark = src.indexOf("advanceTo(ReleaseRecord.Status.SUCCESS)");
        int endBlock = src.indexOf("completeEmitters(record.getId());", deployCall);
        String block = src.substring(deployCall, endBlock);
        assertTrue(block.contains("record.fail("), "部署失败分支必须 record.fail");
        assertTrue(deployCall < successMark, "fail 分支必须先于 SUCCESS 判定");
        // deployToK3s / deployToPreview 返回 boolean
        assertTrue(src.contains("private boolean deployToK3s("), "deployToK3s 应返回 boolean");
        assertTrue(src.contains("private boolean deployToPreview("), "deployToPreview 应返回 boolean");
    }
}
