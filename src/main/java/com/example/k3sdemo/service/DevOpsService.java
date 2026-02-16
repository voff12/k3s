package com.example.k3sdemo.service;

import com.example.k3sdemo.model.PipelineConfig;
import com.example.k3sdemo.model.PipelineRun;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Core DevOps service: orchestrates CI/CD via K3s Job (Kaniko) + Harbor +
 * kubectl deploy.
 */
@Service
public class DevOpsService {

    @Value("${k8s.kubeconfig:}")
    private String kubeconfig;

    @Value("${k8s.master.url:}")
    private String masterUrl;

    @Value("${harbor.host:harbor.local}")
    private String harborHost;

    @Value("${harbor.project:library}")
    private String harborProject;

    @Value("${gitlab.token:}")
    private String globalGitlabToken;

    @Value("${kaniko.image:registry.cn-hangzhou.aliyuncs.com/kaniko-project/executor:latest}")
    private String kanikoImage;

    @Value("${git.image:alpine/git:latest}")
    private String gitImage;

    @Value("${maven.image:maven:3.9-eclipse-temurin-17}")
    private String mavenImage;

    private final Map<String, PipelineRun> pipelineRuns = new ConcurrentHashMap<>();
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

    /**
     * Trigger a new pipeline run.
     */
    public PipelineRun triggerPipeline(PipelineConfig config) {
        PipelineRun run = new PipelineRun(config);
        pipelineRuns.put(run.getId(), run);
        emitters.put(run.getId(), new CopyOnWriteArrayList<>());

        // If no per-pipeline token, use global GitLab token
        if (!config.hasGitAuth() && globalGitlabToken != null && !globalGitlabToken.isEmpty()) {
            config.setGitToken(globalGitlabToken);
        }

        run.addLog("[INFO] 流水线已创建, ID: " + run.getId());
        run.addLog("[INFO] Git仓库: " + config.getGitUrl());
        run.addLog("[INFO] 分支: " + config.getBranch());
        run.addLog("[INFO] 目标镜像: " + config.getFullImageRef(harborHost, harborProject));
        if (config.hasGitAuth()) {
            run.addLog("[INFO] Git认证: 使用 Private Token (GitLab)");
        }

        executor.submit(() -> executePipeline(run));
        return run;
    }

    /**
     * Execute the full CI/CD pipeline.
     */
    private void executePipeline(PipelineRun run) {
        PipelineConfig config = run.getConfig();
        String fullImage = config.getFullImageRef(harborHost, harborProject);
        String jobName = "kaniko-" + run.getId();

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            // ========== Create Job upfront during Clone step ==========
            run.advanceTo(PipelineRun.Status.CLONING);
            broadcastStatus(run);
            run.addLog("[INFO] ➜ 步骤1/6: 代码克隆...");
            if (config.hasGitAuth()) {
                run.addLog("[INFO] 使用 Git Token 认证克隆私有仓库: " + config.getGitUrl());
            } else {
                run.addLog("[INFO] 克隆公开仓库: " + config.getGitUrl());
            }
            broadcastLog(run);

            // ── Layer 1: API 提交防御 ──
            Job kanikoJob = buildKanikoJob(jobName, run.getId(), config, fullImage);
            try {
                client.batch().v1().jobs().inNamespace("default").resource(kanikoJob).create();
            } catch (KubernetesClientException e) {
                int code = e.getCode();
                if (code == 409) {
                    // Conflict — old Job with same name exists, clean up and retry
                    run.addLog("[WARN] Job " + jobName + " 已存在 (409 Conflict), 正在清理并重建...");
                    broadcastLog(run);
                    cleanupJob(client, jobName);
                    Thread.sleep(3000);
                    client.batch().v1().jobs().inNamespace("default").resource(kanikoJob).create();
                } else if (code == 403) {
                    run.fail("权限不足 (403 Forbidden): " + e.getMessage() + "\n请检查 ServiceAccount 权限");
                    broadcastStatus(run);
                    return;
                } else if (code == 422) {
                    run.fail("Job 定义无效 (422 Unprocessable): " + e.getMessage());
                    broadcastStatus(run);
                    return;
                } else {
                    run.fail("K8s API 错误 (" + code + "): " + e.getMessage());
                    broadcastStatus(run);
                    return;
                }
            }
            run.addLog("[INFO] K3s Job 已创建: " + jobName);
            broadcastLog(run);

            String podName = waitForPodName(client, jobName, run);
            if (podName == null) {
                run.fail("Pod 创建超时");
                broadcastStatus(run);
                return;
            }
            run.addLog("[INFO] Pod 已创建: " + podName);
            run.addLog("[INFO] 等待 git-clone 完成...");
            broadcastLog(run);

            boolean cloneOk = waitForInitContainerAndStreamLogs(client, podName, "git-clone", run);
            if (!cloneOk) {
                run.fail("代码克隆失败，请查看日志");
                broadcastStatus(run);
                cleanupJob(client, jobName);
                return;
            }
            run.addLog("[INFO] ✓ 代码克隆完成");
            broadcastLog(run);

            // ========== Step 1: Maven Build ==========
            if (config.hasBuildStep()) {
                run.advanceTo(PipelineRun.Status.PACKAGING);
                broadcastStatus(run);
                run.addLog("[INFO] ➜ 步骤2/6: Maven 打包构建...");
                run.addLog("[INFO] 构建命令: " + config.getBuildCommand());
                broadcastLog(run);

                boolean buildOk = waitForInitContainerAndStreamLogs(client, podName, "maven-build", run);
                if (!buildOk) {
                    run.fail("Maven 打包失败，请查看日志");
                    broadcastStatus(run);
                    cleanupJob(client, jobName);
                    return;
                }
                run.addLog("[INFO] ✓ Maven 打包完成");
                broadcastLog(run);
            } else {
                run.advanceTo(PipelineRun.Status.PACKAGING);
                broadcastStatus(run);
                run.addLog("[INFO] ➜ 步骤2/6: 跳过打包步骤 (无构建命令)");
                broadcastLog(run);
            }

            // ========== Step 2: Kaniko image build ==========
            run.advanceTo(PipelineRun.Status.BUILDING);
            broadcastStatus(run);
            run.addLog("[INFO] ➜ 步骤3/6: Kaniko 镜像构建...");
            broadcastLog(run);

            boolean podRunning = waitForPodRunning(client, podName, run);
            if (!podRunning) {
                run.fail("Kaniko 容器启动失败");
                broadcastStatus(run);
                cleanupJob(client, jobName);
                return;
            }

            // ========== Step 3: Stream Kaniko logs / Push ==========
            run.advanceTo(PipelineRun.Status.PUSHING);
            broadcastStatus(run);
            run.addLog("[INFO] ➜ 步骤4/6: 构建镜像并推送到 Harbor...");
            broadcastLog(run);

            streamContainerLogs(client, podName, "default", "kaniko", run);

            boolean success = waitForJobCompletion(client, jobName, run);
            if (!success) {
                run.fail("Kaniko 构建失败，请查看日志");
                broadcastStatus(run);
                cleanupJob(client, jobName);
                return;
            }

            run.addLog("[INFO] ✓ 镜像构建并推送成功: " + fullImage);
            broadcastLog(run);

            // ========== Step 4: Deploy ==========
            run.advanceTo(PipelineRun.Status.DEPLOYING);
            broadcastStatus(run);
            run.addLog("[INFO] ➜ 步骤5/6: 部署到 K3s 集群...");
            broadcastLog(run);

            if (config.getDeploymentName() != null && !config.getDeploymentName().isEmpty()) {
                deployToK3s(client, config, fullImage, run);
            } else {
                run.addLog("[INFO] 未指定 Deployment, 跳过部署步骤 (仅构建镜像)");
                broadcastLog(run);
            }

            // ========== Step 5: Done ==========
            run.advanceTo(PipelineRun.Status.SUCCESS);
            run.addLog("[INFO] ✓ 流水线执行完成! 总耗时: " + run.getDuration());
            broadcastStatus(run);
            broadcastLog(run);

            cleanupJob(client, jobName);

        } catch (Exception e) {
            run.fail("流水线异常: " + e.getMessage());
            broadcastStatus(run);
        } finally {
            completeEmitters(run.getId());
        }
    }

    // ========== Pod & Container Watching Helpers ==========

    /**
     * Wait for the Job pod to appear (any phase). Returns pod name or null.
     * ── Layer 2: Pod 创建防御 ──
     */
    private String waitForPodName(KubernetesClient client, String jobName, PipelineRun run)
            throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            List<Pod> pods = client.pods().inNamespace("default")
                    .withLabel("job-name", jobName).list().getItems();
            if (!pods.isEmpty()) {
                Pod pod = pods.get(0);
                if ("Failed".equals(pod.getStatus().getPhase())) {
                    String reason = parsePodConditions(pod);
                    run.addLog("[ERROR] Pod 启动失败" + (reason != null ? ": " + reason : ""));
                    String events = parsePodEvents(client, pod.getMetadata().getName());
                    if (events != null)
                        run.addLog("[ERROR] 事件详情: " + events);
                    broadcastLog(run);
                    return null;
                }
                String detail = getPodWaitingReason(pod);
                if (detail != null && (detail.contains("ImagePullBackOff") || detail.contains("ErrImagePull"))) {
                    run.addLog("[ERROR] 镜像拉取失败: " + detail);
                    broadcastLog(run);
                    return null;
                }
                // Check for Unschedulable
                String condition = parsePodConditions(pod);
                if (condition != null && condition.contains("Unschedulable")) {
                    run.addLog("[ERROR] Pod 无法调度: " + condition);
                    broadcastLog(run);
                    return null;
                }
                return pod.getMetadata().getName();
            }
            if (i % 5 == 0 && i > 0) {
                run.addLog("[INFO] 等待 Pod 创建...");
                broadcastLog(run);
            }
            Thread.sleep(5000);
        }
        return null;
    }

    /**
     * Wait for init container to finish, streaming its logs via polling.
     * Uses getLog() polling instead of watchLog() to avoid 400 errors.
     */
    private boolean waitForInitContainerAndStreamLogs(KubernetesClient client, String podName,
            String containerName, PipelineRun run) {
        try {
            int lastLineCount = 0;
            for (int i = 0; i < 360; i++) { // up to 30 min
                Pod pod = client.pods().inNamespace("default").withName(podName).get();
                if (pod == null)
                    return false;
                if ("Failed".equals(pod.getStatus().getPhase()))
                    return false;

                // Check init container status
                String containerState = getInitContainerState(pod, containerName);

                if ("running".equals(containerState) || "terminated".equals(containerState)) {
                    // Container has started or finished, try to fetch logs
                    try {
                        String logs = client.pods().inNamespace("default").withName(podName)
                                .inContainer(containerName).getLog();
                        if (logs != null && !logs.isEmpty()) {
                            String[] lines = logs.split("\n");
                            // Only broadcast new lines
                            for (int j = lastLineCount; j < lines.length; j++) {
                                run.addLog(lines[j]);
                            }
                            if (lines.length > lastLineCount) {
                                lastLineCount = lines.length;
                                broadcastLog(run);
                            }
                        }
                    } catch (Exception logErr) {
                        // Log fetch may fail transiently, continue polling
                    }

                    // ── Layer 4: 构建执行防御 — 精确诊断 exit code ──
                    if ("terminated".equals(containerState)) {
                        return checkInitContainerSucceeded(client, podName, containerName, run);
                    }
                } else if ("waiting".equals(containerState)) {
                    // ── Layer 3: 调度 & 拉镜像防御 ──
                    String reason = getInitContainerWaitingReason(pod, containerName);
                    if (reason != null) {
                        if (reason.contains("ImagePullBackOff") || reason.contains("ErrImagePull")) {
                            run.addLog("[ERROR] " + containerName + " 镜像拉取失败: " + reason);
                            broadcastLog(run);
                            return false;
                        }
                        if (reason.contains("CrashLoopBackOff")) {
                            run.addLog("[ERROR] " + containerName + " 反复崩溃 (CrashLoopBackOff)");
                            broadcastLog(run);
                            return false;
                        }
                        if (reason.contains("CreateContainerConfigError")) {
                            run.addLog("[ERROR] " + containerName + " 配置错误: Secret/ConfigMap 缺失");
                            broadcastLog(run);
                            return false;
                        }
                    }
                    if (i % 6 == 0 && i > 0) {
                        run.addLog("[INFO] " + containerName + " 等待中" +
                                (reason != null ? " (" + reason + ")" : "") + "...");
                        broadcastLog(run);
                    }
                }

                // If pod already Running, all inits are done — check one last time
                if ("Running".equals(pod.getStatus().getPhase())) {
                    // Fetch final logs
                    try {
                        String logs = client.pods().inNamespace("default").withName(podName)
                                .inContainer(containerName).getLog();
                        if (logs != null && !logs.isEmpty()) {
                            String[] lines = logs.split("\n");
                            for (int j = lastLineCount; j < lines.length; j++) {
                                run.addLog(lines[j]);
                            }
                            if (lines.length > lastLineCount) {
                                broadcastLog(run);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    return checkInitContainerSucceeded(client, podName, containerName, run);
                }

                Thread.sleep(5000);
            }
            run.addLog("[ERROR] " + containerName + " 执行超时 (30分钟)");
            broadcastLog(run);
            return false;
        } catch (Exception e) {
            run.addLog("[ERROR] " + containerName + " 异常: " + e.getMessage());
            broadcastLog(run);
            return false;
        }
    }

    /**
     * Get the state of a specific init container: "waiting", "running",
     * "terminated", or null.
     */
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

    /**
     * Get the waiting reason of a specific init container.
     */
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

    /**
     * Check if a specific init container terminated with exit code 0.
     * ── Layer 4: Exit code 诊断 ──
     */
    private boolean checkInitContainerSucceeded(KubernetesClient client, String podName,
            String containerName, PipelineRun run) {
        try {
            Pod pod = client.pods().inNamespace("default").withName(podName).get();
            if (pod == null)
                return false;
            var initStatuses = pod.getStatus().getInitContainerStatuses();
            if (initStatuses != null) {
                for (var cs : initStatuses) {
                    if (containerName.equals(cs.getName()) && cs.getState() != null
                            && cs.getState().getTerminated() != null) {
                        var terminated = cs.getState().getTerminated();
                        int exitCode = terminated.getExitCode();
                        if (exitCode == 0)
                            return true;
                        // Diagnose non-zero exit code
                        String reason = terminated.getReason();
                        String diagnosis = diagnoseExitCode(exitCode, reason);
                        run.addLog("[ERROR] " + containerName + " 失败 (exit=" + exitCode + "): " + diagnosis);
                        broadcastLog(run);
                        return false;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Wait for Pod to reach Running phase (all init containers done).
     */
    private boolean waitForPodRunning(KubernetesClient client, String podName, PipelineRun run)
            throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Pod pod = client.pods().inNamespace("default").withName(podName).get();
            if (pod == null)
                return false;
            String phase = pod.getStatus().getPhase();
            if ("Running".equals(phase) || "Succeeded".equals(phase))
                return true;
            if ("Failed".equals(phase)) {
                String events = parsePodEvents(client, podName);
                if (events != null)
                    run.addLog("[ERROR] Pod 事件: " + events);
                broadcastLog(run);
                return false;
            }
            if (i % 5 == 0 && i > 0) {
                run.addLog("[INFO] 等待 Kaniko 容器启动...");
                broadcastLog(run);
            }
            Thread.sleep(5000);
        }
        return false;
    }

    /**
     * Extract waiting reason from Pod container/init statuses.
     */
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
                        return waiting.getReason() + (waiting.getMessage() != null ? ": " + waiting.getMessage() : "");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Stream logs from a specific container in a pod.
     */
    private void streamContainerLogs(KubernetesClient client, String podName, String namespace,
            String containerName, PipelineRun run) {
        try {
            Thread.sleep(1000);
            LogWatch logWatch = client.pods().inNamespace(namespace).withName(podName)
                    .inContainer(containerName).watchLog();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(logWatch.getOutput()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    run.addLog(line);
                    broadcastLog(run);
                }
            }
        } catch (Exception e) {
            run.addLog("[WARN] " + containerName + " 日志流结束: " + e.getMessage());
        }
    }

    /**
     * Wait for a K3s Job to complete (Success or Failed).
     * ── Layer 4: Job 级别的失败检测 ──
     */
    private boolean waitForJobCompletion(KubernetesClient client, String jobName, PipelineRun run)
            throws InterruptedException {
        for (int i = 0; i < 120; i++) { // wait up to 10 minutes
            Job job = client.batch().v1().jobs().inNamespace("default").withName(jobName).get();
            if (job == null) {
                run.addLog("[ERROR] Job 不存在: " + jobName);
                return false;
            }
            var jobStatus = job.getStatus();
            if (jobStatus != null) {
                if (jobStatus.getSucceeded() != null && jobStatus.getSucceeded() > 0) {
                    return true;
                }
                if (jobStatus.getFailed() != null && jobStatus.getFailed() > 0) {
                    // Try to diagnose kaniko container exit code
                    diagnoseMainContainerFailure(client, jobName, run);
                    return false;
                }
            }
            Thread.sleep(5000);
        }
        run.addLog("[ERROR] Job 执行超时: " + jobName);
        return false;
    }

    /**
     * Deploy the built image to K3s by updating the Deployment.
     */
    private void deployToK3s(KubernetesClient client, PipelineConfig config, String fullImage, PipelineRun run) {
        try {
            String ns = config.getNamespace();
            String deployName = config.getDeploymentName();

            Deployment deployment = client.apps().deployments()
                    .inNamespace(ns).withName(deployName).get();

            if (deployment == null) {
                run.addLog("[WARN] Deployment 不存在: " + deployName + ", 在命名空间: " + ns);
                run.addLog("[INFO] 跳过部署步骤");
                return;
            }

            // Update the first container's image
            deployment.getSpec().getTemplate().getSpec().getContainers().get(0).setImage(fullImage);

            client.apps().deployments().inNamespace(ns).resource(deployment).update();
            run.addLog("[INFO] ✓ Deployment 已更新: " + deployName + " -> " + fullImage);

            // Wait for rollout
            run.addLog("[INFO] 等待滚动更新完成...");
            broadcastLog(run);
            Thread.sleep(3000);

            // Check rollout status
            Deployment updated = client.apps().deployments()
                    .inNamespace(ns).withName(deployName).get();
            if (updated != null && updated.getStatus() != null) {
                int desired = updated.getSpec().getReplicas() != null ? updated.getSpec().getReplicas() : 1;
                int ready = updated.getStatus().getReadyReplicas() != null ? updated.getStatus().getReadyReplicas() : 0;
                run.addLog("[INFO] 副本状态: " + ready + "/" + desired + " Ready");
            }
            broadcastLog(run);

        } catch (Exception e) {
            run.addLog("[ERROR] 部署失败: " + e.getMessage());
        }
    }

    /**
     * Build the Kaniko Job spec.
     * Always uses init containers: git-clone (+ optional maven-build), then Kaniko.
     */
    private Job buildKanikoJob(String jobName, String pipelineId, PipelineConfig config, String fullImage) {
        // Build git clone command
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

        String cloneCommand = String.format(
                "git clone --depth 1 --branch %s %s /workspace && echo '[INFO] Clone completed successfully'",
                config.getBranch(), cloneUrl);

        // Start building the Job
        var jobBuilder = new JobBuilder()
                .withNewMetadata()
                .withName(jobName)
                .withNamespace("default")
                .addToLabels("devops-pipeline", pipelineId)
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(0)
                .withNewTemplate()
                .withNewSpec()
                .withRestartPolicy("Never")
                // Init container 1: Git clone
                .addNewInitContainer()
                .withName("git-clone")
                .withImage(gitImage)
                .withCommand("sh", "-c", cloneCommand)
                .addNewVolumeMount()
                .withName("workspace")
                .withMountPath("/workspace")
                .endVolumeMount()
                .endInitContainer();

        // Init container 2: Maven build (optional)
        if (config.hasBuildStep()) {
            String mvnCommand = String.format(
                    "cd /workspace && %s && echo '[INFO] Build completed successfully'",
                    config.getBuildCommand());
            jobBuilder = jobBuilder
                    .addNewInitContainer()
                    .withName("maven-build")
                    .withImage(mavenImage)
                    .withCommand("sh", "-c", mvnCommand)
                    .addNewVolumeMount()
                    .withName("workspace")
                    .withMountPath("/workspace")
                    .endVolumeMount()
                    .addNewVolumeMount()
                    .withName("maven-repo")
                    .withMountPath("/root/.m2")
                    .endVolumeMount()
                    .endInitContainer();
        }

        // Main container: Kaniko builds from /workspace
        return jobBuilder
                .addNewContainer()
                .withName("kaniko")
                .withImage(kanikoImage)
                .withArgs(
                        "--dockerfile=" + config.getDockerfilePath(),
                        "--context=dir:///workspace",
                        "--destination=" + fullImage,
                        "--insecure",
                        "--skip-tls-verify")
                .addNewVolumeMount()
                .withName("docker-config")
                .withMountPath("/kaniko/.docker")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("workspace")
                .withMountPath("/workspace")
                .endVolumeMount()
                .endContainer()
                // Volumes
                .addNewVolume()
                .withName("docker-config")
                .withNewSecret()
                .withSecretName("harbor-registry-secret")
                .endSecret()
                .endVolume()
                .addNewVolume()
                .withName("workspace")
                .withNewEmptyDir()
                .endEmptyDir()
                .endVolume()
                .addNewVolume()
                .withName("maven-repo")
                .withNewEmptyDir()
                .endEmptyDir()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    /**
     * Cleanup completed Kaniko Job.
     */
    private void cleanupJob(KubernetesClient client, String jobName) {
        try {
            client.batch().v1().jobs().inNamespace("default").withName(jobName)
                    .withPropagationPolicy(DeletionPropagation.BACKGROUND).delete();
        } catch (Exception e) {
            // ignore cleanup errors
        }
    }

    // ========== Defense Helpers ==========

    /**
     * ── Layer 4: Diagnose exit code ──
     */
    private String diagnoseExitCode(int exitCode, String reason) {
        if ("OOMKilled".equals(reason) || exitCode == 137) {
            return "内存溢出 (OOM Killed), 请增加容器内存限制";
        }
        return switch (exitCode) {
            case 1 -> "脚本执行失败 (exit 1), 请检查构建命令或代码";
            case 2 -> "Shell 语法错误或命令误用 (exit 2)";
            case 126 -> "命令无法执行 (权限不足或非可执行文件)";
            case 127 -> "命令未找到 (exit 127), 请检查镜像中是否安装了所需工具";
            case 128 -> "无效的退出信号 (exit 128)";
            case 130 -> "收到 SIGINT 中断信号 (Ctrl+C)";
            case 137 -> "被 SIGKILL 终止 (可能 OOM Killed), 请增加内存限制";
            case 143 -> "收到 SIGTERM 终止信号, 可能被系统清理";
            default -> {
                if (exitCode > 128) {
                    yield "被信号 " + (exitCode - 128) + " 终止";
                }
                yield "未知错误 (exit " + exitCode + ")" + (reason != null ? ", reason=" + reason : "");
            }
        };
    }

    /**
     * ── Layer 4: Diagnose main (kaniko) container failure ──
     */
    private void diagnoseMainContainerFailure(KubernetesClient client, String jobName, PipelineRun run) {
        try {
            List<Pod> pods = client.pods().inNamespace("default")
                    .withLabel("job-name", jobName).list().getItems();
            if (!pods.isEmpty()) {
                Pod pod = pods.get(0);
                var statuses = pod.getStatus().getContainerStatuses();
                if (statuses != null) {
                    for (var cs : statuses) {
                        if (cs.getState() != null && cs.getState().getTerminated() != null) {
                            var terminated = cs.getState().getTerminated();
                            int exitCode = terminated.getExitCode();
                            String reason = terminated.getReason();
                            String diagnosis = diagnoseExitCode(exitCode, reason);
                            run.addLog("[ERROR] " + cs.getName() + " 失败 (exit=" + exitCode + "): " + diagnosis);
                        }
                    }
                }
                broadcastLog(run);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * ── Layer 2: Parse PodConditions for precise scheduling/init failure ──
     */
    private String parsePodConditions(Pod pod) {
        try {
            var conditions = pod.getStatus().getConditions();
            if (conditions != null) {
                for (var c : conditions) {
                    if ("False".equals(c.getStatus()) && c.getReason() != null) {
                        return c.getReason() + (c.getMessage() != null ? ": " + c.getMessage() : "");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * ── Layer 2/3: Fetch recent Kubernetes Events for a Pod ──
     */
    private String parsePodEvents(KubernetesClient client, String podName) {
        try {
            var events = client.v1().events().inNamespace("default")
                    .withField("involvedObject.name", podName).list().getItems();
            if (events != null && !events.isEmpty()) {
                // Get last 3 events
                StringBuilder sb = new StringBuilder();
                int start = Math.max(0, events.size() - 3);
                for (int i = start; i < events.size(); i++) {
                    var ev = events.get(i);
                    if (sb.length() > 0)
                        sb.append(" | ");
                    sb.append(ev.getReason()).append(": ").append(ev.getMessage());
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * ── Layer 5: 兜底超时防御 ── 定时扫描超时流水线
     */
    @Scheduled(fixedRate = 60000)
    public void sweepStalePipelines() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        for (Map.Entry<String, PipelineRun> entry : pipelineRuns.entrySet()) {
            PipelineRun run = entry.getValue();
            if (!run.isFinished() && run.getLastActivityTime().isBefore(cutoff)) {
                run.addLog("[WARN] 流水线超过 30 分钟无活动, 强制终止");
                run.fail("超时被系统强制终止 (30分钟无活动)");
                broadcastStatus(run);
                broadcastLog(run);
                completeEmitters(run.getId());
                // Try to cleanup the K3s Job
                String jobName = "kaniko-" + run.getId();
                try (KubernetesClient client = new KubernetesClientBuilder().build()) {
                    cleanupJob(client, jobName);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ========== SSE Emitter management ==========

    /**
     * Register a new SSE emitter for a pipeline run.
     */
    public SseEmitter createEmitter(String pipelineId) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 min timeout
        PipelineRun run = pipelineRuns.get(pipelineId);

        if (run == null) {
            emitter.completeWithError(new IllegalArgumentException("Pipeline not found: " + pipelineId));
            return emitter;
        }

        List<SseEmitter> list = emitters.computeIfAbsent(pipelineId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));

        // Send existing logs as initial batch
        try {
            List<String> existingLogs = run.getLogs();
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data(Map.of(
                            "status", run.getStatus().name(),
                            "statusLabel", run.getStatus().getLabel(),
                            "currentStep", run.getCurrentStep(),
                            "logs", existingLogs,
                            "finished", run.isFinished(),
                            "duration", run.getDuration())));
        } catch (Exception e) {
            list.remove(emitter);
        }

        // If already finished, complete immediately
        if (run.isFinished()) {
            try {
                emitter.send(SseEmitter.event().name("complete").data(Map.of(
                        "status", run.getStatus().name(),
                        "duration", run.getDuration())));
                emitter.complete();
            } catch (Exception e) {
                // ignore
            }
        }

        return emitter;
    }

    /**
     * Broadcast log update to all SSE emitters for a pipeline.
     */
    private void broadcastLog(PipelineRun run) {
        List<SseEmitter> list = emitters.get(run.getId());
        if (list == null || list.isEmpty())
            return;

        List<String> allLogs = run.getLogs();
        // Send the last log line
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

    /**
     * Broadcast status update to all SSE emitters.
     */
    private void broadcastStatus(PipelineRun run) {
        List<SseEmitter> list = emitters.get(run.getId());
        if (list == null || list.isEmpty())
            return;

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(Map.of(
                                "status", run.getStatus().name(),
                                "statusLabel", run.getStatus().getLabel(),
                                "currentStep", run.getCurrentStep(),
                                "finished", run.isFinished(),
                                "duration", run.getDuration())));
            } catch (Exception e) {
                list.remove(emitter);
            }
        }
    }

    /**
     * Complete all emitters for a pipeline.
     */
    private void completeEmitters(String pipelineId) {
        List<SseEmitter> list = emitters.get(pipelineId);
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

    // ========== Query methods ==========

    public PipelineRun getPipelineRun(String id) {
        return pipelineRuns.get(id);
    }

    public List<PipelineRun> listPipelineRuns() {
        List<PipelineRun> runs = new ArrayList<>(pipelineRuns.values());
        runs.sort((a, b) -> b.getStartTime().compareTo(a.getStartTime()));
        return runs;
    }
}
