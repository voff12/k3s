package com.example.k3sdemo.service;

import com.example.k3sdemo.model.ReleaseConfig;
import com.example.k3sdemo.model.ReleaseRecord;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 应用发布服务: 通过 K8s Job 编排 release-runner 完成
 * clone repo → mvn build → build image → push Harbor → kubectl deploy.
 */
@Service
public class ReleaseService {

    @Value("${k8s.kubeconfig:}")
    private String kubeconfig;

    @Value("${k8s.master.url:}")
    private String masterUrl;

    @Value("${harbor.host:harbor.local}")
    private String harborHost;

    @Value("${harbor.project:library}")
    private String harborProject;

    @Value("${harbor.username:admin}")
    private String harborUsername;

    @Value("${harbor.password:Harbor12345}")
    private String harborPassword;

    @Value("${harbor.ip:}")
    private String harborIp;

    @Value("${gitlab.token:}")
    private String globalGitlabToken;

    @Value("${kaniko.image:registry.aliyuncs.com/kaniko-project/executor:latest}")
    private String kanikoImage;

    @Value("${git.image:alpine:3.19}")
    private String gitImage;

    @Value("${maven.image:maven:3.9-eclipse-temurin-17}")
    private String mavenImage;

    @Value("${git.proxy:}")
    private String globalGitProxy;

    @Value("${release.base-image:}")
    private String releaseBaseImage;

    private final Map<String, ReleaseRecord> releases = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @PostConstruct
    public void init() {
        if (kubeconfig != null && !kubeconfig.isEmpty()) {
            System.setProperty("kubeconfig", kubeconfig);
        }
        if (masterUrl != null && !masterUrl.isEmpty()) {
            System.setProperty("kubernetes.master", masterUrl);
        }
    }

    // ==================== 触发发布 ====================

    public ReleaseRecord triggerRelease(ReleaseConfig config) {
        ReleaseRecord record = new ReleaseRecord(config);
        releases.put(record.getId(), record);
        emitters.put(record.getId(), new CopyOnWriteArrayList<>());

        // 默认 Token
        if (!config.hasGitAuth() && globalGitlabToken != null && !globalGitlabToken.isEmpty()) {
            config.setGitToken(globalGitlabToken);
        }

        String fullImage = config.getFullHarborImageRef(harborHost);
        record.addLog("[INFO] 发布任务已创建, ID: " + record.getId());
        record.addLog("[INFO] Git 仓库: " + config.getGitUrl());
        record.addLog("[INFO] 分支: " + config.getBranch());
        record.addLog("[INFO] 目标镜像: " + fullImage);
        record.addLog("[INFO] Harbor 项目: " + config.getHarborProject());
        if (config.hasGitAuth()) {
            record.addLog("[INFO] Git 认证: 使用 Private Token");
        }

        executor.submit(() -> executeRelease(record));
        return record;
    }

    // ==================== 执行发布 ====================

    private void executeRelease(ReleaseRecord record) {
        ReleaseConfig config = record.getConfig();
        String fullImage = config.getFullHarborImageRef(harborHost);
        String jobName = "release-" + record.getId();

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            // ========== Step 1: 构建发布 (Clone + Maven + Kaniko → Harbor) ==========
            record.advanceTo(ReleaseRecord.Status.BUILDING);
            broadcastStatus(record);
            record.addLog("[INFO] ➜ 步骤 1/2: 构建发布 (克隆 → Maven → Kaniko → Harbor)...");
            broadcastLog(record);

            Job releaseJob = buildReleaseJob(jobName, record.getId(), config, fullImage);
            try {
                client.batch().v1().jobs().inNamespace("default").resource(releaseJob).create();
            } catch (KubernetesClientException e) {
                int code = e.getCode();
                if (code == 409) {
                    record.addLog("[WARN] Job " + jobName + " 已存在, 正在清理重建...");
                    broadcastLog(record);
                    cleanupJob(client, jobName);
                    Thread.sleep(3000);
                    client.batch().v1().jobs().inNamespace("default").resource(releaseJob).create();
                } else {
                    record.fail("K8s API 错误 (" + code + "): " + e.getMessage());
                    broadcastStatus(record);
                    return;
                }
            }
            record.addLog("[INFO] K8s Job 已创建: " + jobName);
            broadcastLog(record);

            String podName = waitForPodName(client, jobName, record);
            if (podName == null) {
                record.fail("Pod 创建超时");
                broadcastStatus(record);
                return;
            }
            record.addLog("[INFO] Pod 已创建: " + podName);
            broadcastLog(record);

            // 1a: build 容器 (Git Clone + Maven + Dockerfile + Docker Config)
            boolean buildOk = waitForInitContainerAndStreamLogs(client, podName, "build", record);
            if (!buildOk) {
                record.fail("构建失败，请查看日志");
                broadcastStatus(record);
                cleanupJob(client, jobName);
                return;
            }
            record.addLog("[INFO] ✓ 代码克隆 + Maven 构建完成, 开始 Kaniko 构建...");
            broadcastLog(record);

            // 1b: Kaniko 构建镜像并推送到 Harbor
            boolean imageOk = waitForInitContainerAndStreamLogs(client, podName, "kaniko-build", record);
            if (!imageOk) {
                diagnoseMainContainerFailure(client, jobName, record);
                record.fail("镜像构建失败，请查看日志");
                broadcastStatus(record);
                return;
            }
            record.addLog("[INFO] ✓ 镜像构建并推送 Harbor 完成: " + fullImage);
            broadcastLog(record);

            // ========== Step 2: K3s 部署 ==========
            record.advanceTo(ReleaseRecord.Status.DEPLOYING);
            broadcastStatus(record);
            record.addLog("[INFO] ➜ 步骤 2/2: 部署到 K3s 集群...");
            broadcastLog(record);

            // Wait for main container (deployer) and stream its logs
            boolean podRunning = waitForPodRunning(client, podName, record);
            if (podRunning) {
                streamContainerLogs(client, podName, "default", "deployer", record);
            }

            boolean jobSuccess = waitForJobCompletion(client, jobName, record);

            if (config.getDeploymentName() != null && !config.getDeploymentName().isEmpty()) {
                if (jobSuccess) {
                    // Also do server-side deployment update as a fallback
                    deployToK3s(client, config, fullImage, record);
                } else {
                    diagnoseMainContainerFailure(client, jobName, record);
                    record.fail("部署容器执行失败");
                    broadcastStatus(record);
                    return;
                }
            } else {
                record.addLog("[INFO] 未指定 Deployment, 跳过 K3s 部署 (仅构建推送镜像)");
                broadcastLog(record);
            }

            // ========== Done ==========
            record.advanceTo(ReleaseRecord.Status.SUCCESS);
            record.addLog("[INFO] ✓ 应用发布完成! 总耗时: " + record.getDuration());
            broadcastStatus(record);
            broadcastLog(record);

        } catch (Exception e) {
            record.fail("发布异常: " + e.getMessage());
            broadcastStatus(record);
        } finally {
            completeEmitters(record.getId());
        }
    }

    // ==================== 构建 K8s Job ====================

    private Job buildReleaseJob(String jobName, String releaseId, ReleaseConfig config, String fullImage) {
        // Build git clone URL (with auth if needed)
        String cloneUrl;
        if (config.hasGitAuth()) {
            String gitUrl = config.getGitUrl();
            if (gitUrl.startsWith("https://")) {
                cloneUrl = "https://oauth2:" + config.getGitToken() + "@" + gitUrl.substring("https://".length());
            } else if (gitUrl.startsWith("http://")) {
                cloneUrl = "http://oauth2:" + config.getGitToken() + "@" + gitUrl.substring("http://".length());
            } else {
                cloneUrl = "https://oauth2:" + config.getGitToken() + "@" + gitUrl;
            }
        } else {
            cloneUrl = config.getGitUrl();
        }

        // Maven 构建命令
        String buildCmd = config.hasBuildStep() ? config.getBuildCommand() : "mvn clean package -DskipTests";

        // Dockerfile 不再使用用户原始路径, 而是由 prepare-kaniko 容器自动生成 Dockerfile.release

        // Kaniko 参数现在直接通过 withArgs 传递, 不再需要构建命令字符串

        // Deployer 命令: 使用 kubectl 更新 Deployment
        String deployCmd;
        if (config.getDeploymentName() != null && !config.getDeploymentName().isEmpty()) {
            deployCmd = String.format(
                    "echo '[INFO] 开始部署到 K3s...' && " +
                            "kubectl set image deployment/%s %s=%s -n %s && " +
                            "echo '[INFO] ✓ Deployment 镜像已更新' && " +
                            "kubectl rollout status deployment/%s -n %s --timeout=120s && " +
                            "echo '[INFO] ✓ 滚动更新完成'",
                    config.getDeploymentName(), config.getDeploymentName(), fullImage, config.getNamespace(),
                    config.getDeploymentName(), config.getNamespace());
        } else {
            deployCmd = "echo '[INFO] 未指定 Deployment, 跳过部署' && exit 0";
        }

        // 构建 Harbor docker config for Kaniko authentication
        String dockerConfigJson = String.format(
                "{\"auths\":{\"%s\":{\"username\":\"%s\",\"password\":\"%s\"}}}",
                harborHost, harborUsername, harborPassword);
        // 基础镜像 (通过 Harbor 代理缓存,避免 Docker Hub 超时)
        String baseImage = (releaseBaseImage != null && !releaseBaseImage.isEmpty())
                ? releaseBaseImage
                : harborHost + "/library/eclipse-temurin:17-jdk-jar";

        // Build 容器命令: Clone → Maven → Dockerfile.release → Docker Config
        StringBuilder buildCmdBuilder = new StringBuilder();
        // 安装 git
        buildCmdBuilder.append("apt-get update -qq && apt-get install -y -qq git > /dev/null 2>&1 && ");
        buildCmdBuilder.append("echo '[INFO] Git 已安装' && ");
        // Git 配置
        buildCmdBuilder.append("git config --global http.version HTTP/1.1 && ");
        buildCmdBuilder.append("git config --global protocol.version 1 && ");
        buildCmdBuilder.append("git config --global http.postBuffer 524288000 && ");
        buildCmdBuilder.append("git config --global http.lowSpeedLimit 1000 && ");
        buildCmdBuilder.append("git config --global http.lowSpeedTime 30 && ");
        if (globalGitProxy != null && !globalGitProxy.isEmpty()) {
            buildCmdBuilder.append("git config --global http.proxy ").append(globalGitProxy).append(" && ");
            buildCmdBuilder.append("git config --global https.proxy ").append(globalGitProxy).append(" && ");
        }
        // 克隆代码
        buildCmdBuilder.append("git clone --depth 1 --branch ").append(config.getBranch())
                .append(" ").append(cloneUrl).append(" /workspace && ");
        buildCmdBuilder.append("echo '[INFO] ✓ 代码克隆完成' && ");
        // Maven 构建
        buildCmdBuilder.append("cd /workspace && ").append(buildCmd).append(" && ");
        buildCmdBuilder.append("echo '[INFO] ✓ Maven 构建完成' && ");
        buildCmdBuilder.append("ls -la /workspace/target/*.jar 2>/dev/null && ");
        // 生成 Dockerfile.release
        buildCmdBuilder.append(String.format(
                "printf 'FROM %s\\nWORKDIR /app\\nCOPY target/*.jar app.jar\\nEXPOSE 8080\\n"
                        + "ENV JAVA_OPTS=\"-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0\"\\n"
                        + "ENTRYPOINT [\"sh\", \"-c\", \"java $JAVA_OPTS -jar /app/app.jar\"]\\n' "
                        + "> /workspace/Dockerfile.release && ",
                baseImage));
        buildCmdBuilder.append("echo '[INFO] ✓ Dockerfile.release 已生成 (base: ").append(baseImage).append(")' && ");
        // 写入 Harbor Docker Config
        buildCmdBuilder.append("mkdir -p /docker-config && ");
        buildCmdBuilder.append("echo '").append(dockerConfigJson).append("' > /docker-config/config.json && ");
        buildCmdBuilder.append("echo '[INFO] ✓ Harbor 认证已写入'");

        return new JobBuilder()
                .withNewMetadata()
                .withName(jobName)
                .withNamespace("default")
                .addToLabels("release-pipeline", releaseId)
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(0)
                .withTtlSecondsAfterFinished(3600) // 1小时后自动清理
                .withNewTemplate()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withServiceAccountName("default")
                // Harbor 域名解析 (hostAliases → /etc/hosts)
                .addNewHostAlias()
                .withIp(harborIp != null && !harborIp.isEmpty() ? harborIp : "127.0.0.1")
                .withHostnames(harborHost)
                .endHostAlias()

                // ===== Init Container 1: Build (Clone + Maven + Dockerfile + Docker Config)
                // =====
                .addNewInitContainer()
                .withName("build")
                .withImage(mavenImage)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("sh", "-c", buildCmdBuilder.toString())
                .addNewVolumeMount()
                .withName("workspace")
                .withMountPath("/workspace")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("maven-repo")
                .withMountPath("/root/.m2")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("docker-config")
                .withMountPath("/docker-config")
                .endVolumeMount()
                .endInitContainer()

                // ===== Init Container 2: Kaniko Build & Push to Harbor =====
                // 使用生成的 Dockerfile.release (runtime-only), 直接打包 JAR
                .addNewInitContainer()
                .withName("kaniko-build")
                .withImage(kanikoImage)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/kaniko/executor")
                .withArgs(
                        "--dockerfile=/workspace/Dockerfile.release",
                        "--context=dir:///workspace",
                        "--destination=" + fullImage,
                        "--insecure",
                        "--skip-tls-verify",
                        "--cache=false",
                        "--verbosity=info")
                .addNewVolumeMount()
                .withName("workspace")
                .withMountPath("/workspace")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("docker-config")
                .withMountPath("/kaniko/.docker")
                .endVolumeMount()
                .endInitContainer()

                // ===== Main Container: Deployer (kubectl) =====
                .addNewContainer()
                .withName("deployer")
                .withImage("bitnami/kubectl:latest")
                .withImagePullPolicy("IfNotPresent")
                .withCommand("sh", "-c", deployCmd)
                .endContainer()

                // ===== Volumes =====
                .addNewVolume()
                .withName("workspace")
                .withNewEmptyDir().endEmptyDir()
                .endVolume()
                .addNewVolume()
                .withName("maven-repo")
                .withNewPersistentVolumeClaim()
                .withClaimName("maven-repo-pvc")
                .endPersistentVolumeClaim()
                .endVolume()
                .addNewVolume()
                .withName("docker-config")
                .withNewEmptyDir().endEmptyDir()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    // ==================== Pod & Container 辅助方法 ====================

    private String waitForPodName(KubernetesClient client, String jobName, ReleaseRecord record)
            throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            List<Pod> pods = client.pods().inNamespace("default")
                    .withLabel("job-name", jobName).list().getItems();
            if (!pods.isEmpty()) {
                Pod pod = pods.get(0);
                if ("Failed".equals(pod.getStatus().getPhase())) {
                    record.addLog("[ERROR] Pod 启动失败");
                    broadcastLog(record);
                    return null;
                }
                String detail = getPodWaitingReason(pod);
                if (detail != null && (detail.contains("ImagePullBackOff") || detail.contains("ErrImagePull"))) {
                    record.addLog("[ERROR] 镜像拉取失败: " + detail);
                    broadcastLog(record);
                    return null;
                }
                return pod.getMetadata().getName();
            }
            if (i % 5 == 0 && i > 0) {
                record.addLog("[INFO] 等待 Pod 创建...");
                broadcastLog(record);
            }
            Thread.sleep(5000);
        }
        return null;
    }

    private boolean waitForInitContainerAndStreamLogs(KubernetesClient client, String podName,
            String containerName, ReleaseRecord record) {
        try {
            int lastLineCount = 0;
            for (int i = 0; i < 360; i++) { // up to 30 min
                Pod pod = client.pods().inNamespace("default").withName(podName).get();
                if (pod == null)
                    return false;
                String containerState = getInitContainerState(pod, containerName);

                if ("running".equals(containerState) || "terminated".equals(containerState)) {
                    try {
                        String logs = client.pods().inNamespace("default").withName(podName)
                                .inContainer(containerName).getLog();
                        if (logs != null && !logs.isEmpty()) {
                            String[] lines = logs.split("\n");
                            for (int j = lastLineCount; j < lines.length; j++) {
                                record.addLog(lines[j]);
                            }
                            if (lines.length > lastLineCount) {
                                lastLineCount = lines.length;
                                broadcastLog(record);
                            }
                        }
                    } catch (Exception logErr) {
                        // transient, continue polling
                    }

                    if ("terminated".equals(containerState)) {
                        return checkInitContainerSucceeded(client, podName, containerName, record);
                    }
                }

                if ("Failed".equals(pod.getStatus().getPhase()))
                    return false;

                if (checkAnyInitContainerFailed(pod, record))
                    return false;

                if ("waiting".equals(containerState)) {
                    String reason = getInitContainerWaitingReason(pod, containerName);
                    if (reason != null) {
                        if (reason.contains("ImagePullBackOff") || reason.contains("ErrImagePull")) {
                            record.addLog("[ERROR] " + containerName + " 镜像拉取失败: " + reason);
                            broadcastLog(record);
                            return false;
                        }
                        if (reason.contains("CrashLoopBackOff")) {
                            record.addLog("[ERROR] " + containerName + " 反复崩溃");
                            broadcastLog(record);
                            return false;
                        }
                    }
                    if (i % 6 == 0 && i > 0) {
                        record.addLog("[INFO] " + containerName + " 等待中" +
                                (reason != null ? " (" + reason + ")" : "") + "...");
                        broadcastLog(record);
                    }
                }

                if ("Running".equals(pod.getStatus().getPhase())) {
                    try {
                        String logs = client.pods().inNamespace("default").withName(podName)
                                .inContainer(containerName).getLog();
                        if (logs != null && !logs.isEmpty()) {
                            String[] lines = logs.split("\n");
                            for (int j = lastLineCount; j < lines.length; j++) {
                                record.addLog(lines[j]);
                            }
                            if (lines.length > lastLineCount)
                                broadcastLog(record);
                        }
                    } catch (Exception ignored) {
                    }
                    return checkInitContainerSucceeded(client, podName, containerName, record);
                }

                Thread.sleep(5000);
            }
            record.addLog("[ERROR] " + containerName + " 执行超时 (30分钟)");
            broadcastLog(record);
            return false;
        } catch (Exception e) {
            record.addLog("[ERROR] " + containerName + " 异常: " + e.getMessage());
            broadcastLog(record);
            return false;
        }
    }

    private String getInitContainerState(Pod pod, String containerName) {
        var initStatuses = pod.getStatus().getInitContainerStatuses();
        if (initStatuses != null) {
            for (var cs : initStatuses) {
                if (containerName.equals(cs.getName()) && cs.getState() != null) {
                    if (cs.getState().getTerminated() != null)
                        return "terminated";
                    if (cs.getState().getRunning() != null)
                        return "running";
                    if (cs.getState().getWaiting() != null)
                        return "waiting";
                }
            }
        }
        return null;
    }

    private String getInitContainerWaitingReason(Pod pod, String containerName) {
        var initStatuses = pod.getStatus().getInitContainerStatuses();
        if (initStatuses != null) {
            for (var cs : initStatuses) {
                if (containerName.equals(cs.getName()) && cs.getState() != null
                        && cs.getState().getWaiting() != null) {
                    return cs.getState().getWaiting().getReason();
                }
            }
        }
        return null;
    }

    private boolean checkInitContainerSucceeded(KubernetesClient client, String podName,
            String containerName, ReleaseRecord record) {
        try {
            Pod pod = client.pods().inNamespace("default").withName(podName).get();
            if (pod == null)
                return false;
            var initStatuses = pod.getStatus().getInitContainerStatuses();
            if (initStatuses != null) {
                for (var cs : initStatuses) {
                    if (containerName.equals(cs.getName()) && cs.getState() != null
                            && cs.getState().getTerminated() != null) {
                        int exitCode = cs.getState().getTerminated().getExitCode();
                        if (exitCode == 0)
                            return true;
                        String reason = cs.getState().getTerminated().getReason();
                        record.addLog("[ERROR] " + containerName + " 失败 (exit=" + exitCode + "): " +
                                diagnoseExitCode(exitCode, reason));
                        broadcastLog(record);
                        return false;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean checkAnyInitContainerFailed(Pod pod, ReleaseRecord record) {
        var initStatuses = pod.getStatus().getInitContainerStatuses();
        if (initStatuses != null) {
            for (var cs : initStatuses) {
                if (cs.getState() != null && cs.getState().getTerminated() != null) {
                    int exitCode = cs.getState().getTerminated().getExitCode();
                    if (exitCode != 0) {
                        String reason = cs.getState().getTerminated().getReason();
                        record.addLog(String.format("[ERROR] 前置容器 %s 失败 (exit %d): %s",
                                cs.getName(), exitCode, reason));
                        broadcastLog(record);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean waitForPodRunning(KubernetesClient client, String podName, ReleaseRecord record)
            throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Pod pod = client.pods().inNamespace("default").withName(podName).get();
            if (pod == null)
                return false;
            String phase = pod.getStatus().getPhase();
            if ("Running".equals(phase) || "Succeeded".equals(phase))
                return true;
            if ("Failed".equals(phase))
                return false;
            if (i % 5 == 0 && i > 0) {
                record.addLog("[INFO] 等待部署容器启动...");
                broadcastLog(record);
            }
            Thread.sleep(5000);
        }
        return false;
    }

    private void streamContainerLogs(KubernetesClient client, String podName, String namespace,
            String containerName, ReleaseRecord record) {
        try {
            int lastLineCount = 0;
            for (int i = 0; i < 120; i++) {
                Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
                if (pod == null)
                    return;

                String phase = pod.getStatus().getPhase();
                if ("Failed".equals(phase)) {
                    record.addLog("[ERROR] Pod 状态为 Failed");
                    broadcastLog(record);
                    return;
                }

                boolean containerReady = false;
                boolean containerTerminated = false;
                if (pod.getStatus().getContainerStatuses() != null) {
                    for (var cs : pod.getStatus().getContainerStatuses()) {
                        if (containerName.equals(cs.getName()) && cs.getState() != null) {
                            if (cs.getState().getRunning() != null)
                                containerReady = true;
                            if (cs.getState().getTerminated() != null) {
                                containerReady = true;
                                containerTerminated = true;
                            }
                        }
                    }
                }

                if (containerReady) {
                    try {
                        String logs = client.pods().inNamespace(namespace).withName(podName)
                                .inContainer(containerName).getLog();
                        if (logs != null && !logs.isEmpty()) {
                            String[] lines = logs.split("\n");
                            for (int j = lastLineCount; j < lines.length; j++) {
                                record.addLog(lines[j]);
                            }
                            if (lines.length > lastLineCount) {
                                lastLineCount = lines.length;
                                broadcastLog(record);
                            }
                        }
                    } catch (Exception logErr) {
                        // transient
                    }
                }

                if (containerTerminated || "Succeeded".equals(phase))
                    return;
                Thread.sleep(5000);
            }
        } catch (Exception e) {
            record.addLog("[WARN] " + containerName + " 日志流结束");
        }
    }

    private boolean waitForJobCompletion(KubernetesClient client, String jobName, ReleaseRecord record)
            throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            Job job = client.batch().v1().jobs().inNamespace("default").withName(jobName).get();
            if (job == null) {
                record.addLog("[ERROR] Job 不存在: " + jobName);
                return false;
            }
            var jobStatus = job.getStatus();
            if (jobStatus != null) {
                if (jobStatus.getSucceeded() != null && jobStatus.getSucceeded() > 0)
                    return true;
                if (jobStatus.getFailed() != null && jobStatus.getFailed() > 0)
                    return false;
            }
            Thread.sleep(5000);
        }
        record.addLog("[ERROR] Job 执行超时");
        return false;
    }

    private void deployToK3s(KubernetesClient client, ReleaseConfig config, String fullImage, ReleaseRecord record) {
        try {
            String ns = config.getNamespace();
            String deployName = config.getDeploymentName();

            Deployment deployment = client.apps().deployments()
                    .inNamespace(ns).withName(deployName).get();

            if (deployment == null) {
                record.addLog("[WARN] Deployment 不存在: " + deployName + ", 将自动创建...");
                broadcastLog(record);
                return;
            }

            var container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
            container.setImage(fullImage);
            container.setImagePullPolicy("Always");

            client.apps().deployments().inNamespace(ns).resource(deployment).update();
            record.addLog("[INFO] ✓ Deployment 已更新: " + deployName + " → " + fullImage);

            record.addLog("[INFO] 等待滚动更新...");
            broadcastLog(record);
            Thread.sleep(3000);

            Deployment updated = client.apps().deployments()
                    .inNamespace(ns).withName(deployName).get();
            if (updated != null && updated.getStatus() != null) {
                int desired = updated.getSpec().getReplicas() != null ? updated.getSpec().getReplicas() : 1;
                int ready = updated.getStatus().getReadyReplicas() != null ? updated.getStatus().getReadyReplicas() : 0;
                record.addLog("[INFO] 副本状态: " + ready + "/" + desired + " Ready");
            }
            broadcastLog(record);
        } catch (Exception e) {
            record.addLog("[ERROR] 部署更新失败: " + e.getMessage());
            broadcastLog(record);
        }
    }

    private String getPodWaitingReason(Pod pod) {
        try {
            var statuses = pod.getStatus().getContainerStatuses();
            if (statuses == null || statuses.isEmpty()) {
                statuses = pod.getStatus().getInitContainerStatuses();
            }
            if (statuses != null) {
                for (var cs : statuses) {
                    var waiting = cs.getState() != null ? cs.getState().getWaiting() : null;
                    if (waiting != null && waiting.getReason() != null) {
                        return waiting.getReason();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void cleanupJob(KubernetesClient client, String jobName) {
        try {
            client.batch().v1().jobs().inNamespace("default").withName(jobName)
                    .withPropagationPolicy(DeletionPropagation.BACKGROUND).delete();
        } catch (Exception e) {
            // ignore
        }
    }

    private String diagnoseExitCode(int exitCode, String reason) {
        if ("OOMKilled".equals(reason) || exitCode == 137) {
            return "内存溢出 (OOM), 请增加容器内存限制";
        }
        return switch (exitCode) {
            case 1 -> "脚本执行失败, 请检查构建命令或代码";
            case 2 -> "Shell 语法错误";
            case 126 -> "命令无法执行 (权限不足)";
            case 127 -> "命令未找到, 请检查镜像";
            case 137 -> "被 SIGKILL 终止 (可能 OOM)";
            case 143 -> "收到 SIGTERM 终止信号";
            default -> "错误 (exit " + exitCode + ")" + (reason != null ? ", " + reason : "");
        };
    }

    private void diagnoseMainContainerFailure(KubernetesClient client, String jobName, ReleaseRecord record) {
        try {
            List<Pod> pods = client.pods().inNamespace("default")
                    .withLabel("job-name", jobName).list().getItems();
            if (pods.isEmpty())
                return;
            Pod pod = pods.get(0);
            String podName = pod.getMetadata().getName();

            record.addLog("=== 错误诊断 (" + podName + ") ===");

            var initStatuses = pod.getStatus().getInitContainerStatuses();
            if (initStatuses != null) {
                for (var cs : initStatuses) {
                    if (cs.getState() != null && cs.getState().getTerminated() != null) {
                        int exitCode = cs.getState().getTerminated().getExitCode();
                        if (exitCode != 0) {
                            record.addLog("❌ [" + cs.getName() + "] exit=" + exitCode + ": " +
                                    diagnoseExitCode(exitCode, cs.getState().getTerminated().getReason()));
                            try {
                                String logs = client.pods().inNamespace("default").withName(podName)
                                        .inContainer(cs.getName()).tailingLines(10).getLog();
                                if (logs != null)
                                    record.addLog("日志: " + logs);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
            broadcastLog(record);
        } catch (Exception e) {
            record.addLog("[ERROR] 诊断失败: " + e.getMessage());
            broadcastLog(record);
        }
    }

    // ==================== 超时清理 ====================

    @Scheduled(fixedRate = 60000)
    public void sweepStaleReleases() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        for (Map.Entry<String, ReleaseRecord> entry : releases.entrySet()) {
            ReleaseRecord record = entry.getValue();
            if (!record.isFinished() && record.getLastActivityTime().isBefore(cutoff)) {
                record.addLog("[WARN] 发布超过 30 分钟无活动, 强制终止");
                record.fail("超时被系统终止");
                broadcastStatus(record);
                broadcastLog(record);
                completeEmitters(record.getId());
                String jobName = "release-" + record.getId();
                try (KubernetesClient client = new KubernetesClientBuilder().build()) {
                    cleanupJob(client, jobName);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ==================== SSE Emitter ====================

    public SseEmitter createEmitter(String releaseId) {
        SseEmitter emitter = new SseEmitter(600_000L);
        ReleaseRecord record = releases.get(releaseId);

        if (record == null) {
            emitter.completeWithError(new IllegalArgumentException("Release not found: " + releaseId));
            return emitter;
        }

        List<SseEmitter> list = emitters.computeIfAbsent(releaseId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data(Map.of(
                            "status", record.getStatus().name(),
                            "statusLabel", record.getStatus().getLabel(),
                            "currentStep", record.getCurrentStep(),
                            "logs", record.getLogs(),
                            "finished", record.isFinished(),
                            "duration", record.getDuration())));
        } catch (Exception e) {
            list.remove(emitter);
        }

        if (record.isFinished()) {
            try {
                emitter.send(SseEmitter.event().name("complete").data(Map.of(
                        "status", record.getStatus().name(),
                        "duration", record.getDuration())));
                emitter.complete();
            } catch (Exception e) {
                // ignore
            }
        }

        return emitter;
    }

    private void broadcastLog(ReleaseRecord record) {
        List<SseEmitter> list = emitters.get(record.getId());
        if (list == null || list.isEmpty())
            return;

        List<String> allLogs = record.getLogs();
        if (allLogs.isEmpty())
            return;
        String lastLine = allLogs.get(allLogs.size() - 1);

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("log")
                        .data(Map.of("line", lastLine, "index", allLogs.size() - 1)));
            } catch (Exception e) {
                list.remove(emitter);
            }
        }
    }

    private void broadcastStatus(ReleaseRecord record) {
        List<SseEmitter> list = emitters.get(record.getId());
        if (list == null || list.isEmpty())
            return;

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(Map.of(
                                "status", record.getStatus().name(),
                                "statusLabel", record.getStatus().getLabel(),
                                "currentStep", record.getCurrentStep(),
                                "finished", record.isFinished(),
                                "duration", record.getDuration())));
            } catch (Exception e) {
                list.remove(emitter);
            }
        }
    }

    private void completeEmitters(String releaseId) {
        List<SseEmitter> list = emitters.get(releaseId);
        if (list == null)
            return;

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("complete").data("done"));
                emitter.complete();
            } catch (Exception e) {
                // ignore
            }
        }
        list.clear();
    }

    // ==================== 查询方法 ====================

    public ReleaseRecord getReleaseRecord(String id) {
        return releases.get(id);
    }

    public List<ReleaseRecord> listReleaseRecords() {
        List<ReleaseRecord> list = new ArrayList<>(releases.values());
        list.sort((a, b) -> b.getStartTime().compareTo(a.getStartTime()));
        return list;
    }
}
