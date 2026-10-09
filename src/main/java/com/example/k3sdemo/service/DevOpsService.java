package com.example.k3sdemo.service;

import com.example.k3sdemo.model.PipelineConfig;
import com.example.k3sdemo.model.PipelineRun;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
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
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

/**
 * Core DevOps service: orchestrates CI/CD via K3s Job (BuildKit) + Harbor +
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

    @Value("${buildkit.image:moby/buildkit:v0.25.1-rootless}")
    private String buildkitImage;

    @Value("${buildkit.memory-limit:4Gi}")
    private String buildkitMemoryLimit;

    @Value("${git.image:alpine/git:latest}")
    private String gitImage;

    /** buildkitd 状态缓存 PVC：跨 Job 复用基础镜像层，避免每次构建重拉基础镜像。 */
    public static final String BUILDKIT_CACHE_PVC_NAME = "buildkit-cache-pvc";

    @Value("${maven.image:maven:3.9-eclipse-temurin-17}")
    private String mavenImage;

    @Value("${loader.image:rancher/k3s:latest}")
    private String loaderImage;

    @Value("${git.proxy:}")
    private String globalGitProxy;

    @Value("${local.registry:${harbor.host:harbor.local}}")
    private String localRegistry;

    @Value("${pip.index:https://pypi.tuna.tsinghua.edu.cn/simple}")
    private String pipIndexUrl;

    private final Map<String, PipelineRun> pipelineRuns = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /** 流水线持久化（可为空：交付中心数据源不可用时退化为纯内存模式） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.k3sdemo.delivery.service.PipelineRunPersistenceService persistence;

    @PostConstruct
    public void init() {
        if (kubeconfig != null && !kubeconfig.isEmpty()) {
            System.setProperty("kubeconfig", kubeconfig);
        }
        if (masterUrl != null && !masterUrl.isEmpty()) {
            System.setProperty("kubernetes.master", masterUrl);
        }
        restoreFromDatabase();
    }

    /**
     * 启动时从数据库恢复流水线历史：
     * - 已结束的记录直接回填内存（供列表/详情/SSE init 查询）；
     * - 状态停在非终态的"僵尸任务"（进程重启前正在跑）标记为 FAILED。
     */
    private void restoreFromDatabase() {
        if (persistence == null) {
            return;
        }
        try {
            persistence.markZombiesFailed();
            List<PipelineRun> restored = persistence.loadAll();
            for (PipelineRun run : restored) {
                pipelineRuns.put(run.getId(), run);
                emitters.put(run.getId(), new CopyOnWriteArrayList<>());
            }
        } catch (Exception e) {
            System.err.println("[DevOpsService] 流水线历史恢复失败（不影响新任务）: " + e.getMessage());
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
        // If no per-pipeline proxy, use global git proxy
        if (!config.hasGitProxy() && globalGitProxy != null && !globalGitProxy.isEmpty()) {
            config.setGitProxy(globalGitProxy);
        }

        run.addLog("[INFO] 流水线已创建, ID: " + run.getId());
        run.addLog("[INFO] Git仓库: " + config.getGitUrl());
        if (config.isMergeDeploy()) {
            run.addLog("[INFO] 模式: 多分支合并预览部署");
            run.addLog("[INFO] 基底分支(base): " + config.getEffectiveBaseBranch());
            run.addLog("[INFO] 待合并分支: " + String.join(", ", config.getNormalizedFeatureBranches()));
            run.addLog("[INFO] 预览命名空间: " + config.getPreviewNamespace());
        } else {
            run.addLog("[INFO] 分支: " + config.getBranch());
        }
        run.addLog("[INFO] 目标镜像: " + config.getFullImageRef(harborHost, harborProject));
        if (config.hasGitAuth()) {
            run.addLog("[INFO] Git认证: 使用 Private Token (GitLab)");
        }
        if (config.hasGitProxy()) {
            run.addLog("[INFO] Git代理: " + config.getGitProxy());
        }

        persist(run, false);
        executor.submit(() -> executePipeline(run));
        return run;
    }

    /**
     * Execute the full CI/CD pipeline.
     */
    private void executePipeline(PipelineRun run) {
        PipelineConfig config = run.getConfig();
        String fullImage = config.getFullImageRef(harborHost, harborProject);
        String jobName = "buildkit-" + run.getId();

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            // ========== Create Job upfront during Clone step ==========
            run.advanceTo(PipelineRun.Status.CLONING);
            broadcastStatus(run);
            try {
                run.addLog("[INFO] K8s API Server: " + client.getConfiguration().getMasterUrl());
            } catch (Exception ignore) {
            }
            run.addLog("[INFO] ➜ 步骤1/5: 代码克隆...");
            if (config.hasGitAuth()) {
                run.addLog("[INFO] 使用 Git Token 认证克隆私有仓库: " + config.getGitUrl());
            } else {
                run.addLog("[INFO] 克隆公开仓库: " + config.getGitUrl());
            }
            broadcastLog(run);

            run.addLog("[INFO] 工作区缓存 PVC: " + workspacePvcName(config.getGitUrl())
                    + " (同仓库增量 clone, 二次构建仅 fetch 增量)");
            broadcastLog(run);

            // ── Layer 1: API 提交防御 ──
            Job buildkitJob = buildBuildkitJob(jobName, run.getId(), config, fullImage);
            try {
                ensureWorkspacePvc(client, workspacePvcName(config.getGitUrl()));
                ensureBuildkitCachePvc(client);
                client.batch().v1().jobs().inNamespace("default").resource(buildkitJob).create();
            } catch (KubernetesClientException e) {
                int code = e.getCode();
                if (code == 409) {
                    // Conflict — old Job with same name exists, clean up and retry
                    run.addLog("[WARN] Job " + jobName + " 已存在 (409 Conflict), 正在清理并重建...");
                    broadcastLog(run);
                    cleanupJob(client, jobName);
                    Thread.sleep(3000);
                    ensureWorkspacePvc(client, workspacePvcName(config.getGitUrl()));
                    ensureBuildkitCachePvc(client);
                    client.batch().v1().jobs().inNamespace("default").resource(buildkitJob).create();
                } else if (code == 403) {
                    run.fail("权限不足 (403 Forbidden): " + e.getMessage() + "\n请检查 ServiceAccount 权限");
                    broadcastStatus(run);
                    return;
                } else if (code == 422) {
                    run.fail("Job 定义无效 (422 Unprocessable): " + e.getMessage());
                    broadcastStatus(run);
                    return;
                } else {
                    // code == -1 多为传输层错误(连不上 API Server / 超时 / TLS / DNS)
                    String hint = code == -1
                            ? " — 连不上 K8s API Server(网络/防火墙到 6443、kubeconfig server 地址、或 TLS)。"
                            : "";
                    run.fail("K8s API 错误 (" + code + "): " + e.getMessage() + hint
                            + " | 根因: " + rootCauseMsg(e));
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
                if (config.isMergeDeploy()) {
                    parseMergeResults(run);
                    if (!run.getConflictFiles().isEmpty()) {
                        run.fail("分支合并冲突，冲突文件: " + String.join(", ", run.getConflictFiles())
                                + " — 请人工解决后重试");
                    } else {
                        run.fail("分支合并/克隆失败，请查看日志");
                    }
                } else {
                    run.fail("代码克隆失败，请查看日志");
                }
                broadcastStatus(run);
                cleanupJob(client, jobName);
                return;
            }

            if (config.isMergeDeploy()) {
                run.advanceTo(PipelineRun.Status.MERGING);
                broadcastStatus(run);
                parseMergeResults(run);
                run.addLog("[INFO] ✓ 多分支合并完成"
                        + (run.getMergeCommitSha() != null ? " (merge commit: " + run.getMergeCommitSha() + ")" : ""));
                broadcastLog(run);
            } else {
                run.addLog("[INFO] ✓ 代码克隆完成");
                broadcastLog(run);
            }

            // ========== Step 2: BuildKit 多阶段构建 (Maven打包 + 镜像构建一体化) ==========
            run.advanceTo(PipelineRun.Status.BUILDING);
            broadcastStatus(run);
            String rt = config.resolveRuntime();
            String buildDesc = config.isPython() ? "pip 安装 + 镜像构建"
                    : ("java".equals(rt) ? "Maven 打包 + 镜像构建" : "依赖安装 + 镜像构建");
            run.addLog("[INFO] ➜ 步骤2/5: BuildKit 构建 (" + buildDesc + ")...");
            run.addLog("[INFO] 运行时: " + rt + " | 基础镜像源: " + localRegistry);
            broadcastLog(run);

            // 2a: Wait for rewrite-dockerfile init container (生成多阶段 Dockerfile)
            boolean rewriteOk = waitForInitContainerAndStreamLogs(client, podName, "rewrite-dockerfile", run);
            if (!rewriteOk) {
                diagnoseMainContainerFailure(client, jobName, run);
                run.fail("Dockerfile 生成失败，请查看日志");
                broadcastStatus(run);
                return;
            }

            // 2b: Wait for buildkit init container (执行多阶段构建)
            boolean buildkitOk = waitForInitContainerAndStreamLogs(client, podName, "buildkit", run);
            if (!buildkitOk) {
                diagnoseMainContainerFailure(client, jobName, run);
                run.fail("BuildKit 构建失败，请查看日志");
                broadcastStatus(run);
                return;
            }

            // ========== Step 3: Import to K3s (Main container) ==========
            run.advanceTo(PipelineRun.Status.PUSHING);
            broadcastStatus(run);
            run.addLog("[INFO] ➜ 步骤3/5: 导入镜像到 K3s 节点...");
            broadcastLog(run);

            boolean podRunning = waitForPodRunning(client, podName, run);
            if (!podRunning) {
                diagnoseMainContainerFailure(client, jobName, run);
                run.fail("镜像导入容器启动失败");
                broadcastStatus(run);
                // cleanupJob(client, jobName); // Keep for debugging
                return;
            }

            // Stream loader logs
            streamContainerLogs(client, jobName, "default", "loader", run);

            boolean success = waitForJobCompletion(client, jobName, run);
            if (!success) {
                diagnoseMainContainerFailure(client, jobName, run);
                run.fail("镜像导入失败，请查看日志");
                broadcastStatus(run);
                // cleanupJob(client, jobName); // Keep for debugging
                return;
            }

            run.addLog("[INFO] ✓ 镜像已导入 K3s containerd (离线模式): " + fullImage);
            broadcastLog(run);

            // ========== Step 4: Deploy ==========
            run.advanceTo(PipelineRun.Status.DEPLOYING);
            broadcastStatus(run);
            run.addLog("[INFO] ➜ 步骤4/5: 部署到 K3s 集群...");
            broadcastLog(run);

            if (config.isMergeDeploy()) {
                // 合并模式: 部署到独立预览命名空间 preview-<mergeSetId>
                deployToPreview(client, config, fullImage, run);
            } else if (config.getDeploymentName() != null && !config.getDeploymentName().isEmpty()) {
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

            // cleanupJob(client, jobName); // Keep for debugging

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
            for (int i = 0; i < 1080; i++) { // up to 90 min: BuildKit 冷缓存首次推 mode=max 全量缓存层到 Harbor 较慢
                Pod pod = client.pods().inNamespace("default").withName(podName).get();
                if (pod == null)
                    return false;
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
                }

                // ── Fail Checks (If not terminated yet) ──
                if ("Failed".equals(pod.getStatus().getPhase()))
                    return false;

                // ── Layer 4 Fast Fail: Check if ANY init container failed ──
                if (checkAnyInitContainerFailed(pod, run)) {
                    return false;
                }

                if ("waiting".equals(containerState)) {
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
            run.addLog("[ERROR] " + containerName + " 执行超时 (90分钟)");
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

    private boolean checkAnyInitContainerFailed(Pod pod, PipelineRun run) {
        var initStatuses = pod.getStatus().getInitContainerStatuses();
        if (initStatuses != null) {
            for (var cs : initStatuses) {
                if (cs.getState() != null && cs.getState().getTerminated() != null) {
                    int exitCode = cs.getState().getTerminated().getExitCode();
                    if (exitCode != 0) {
                        String reason = cs.getState().getTerminated().getReason();
                        run.addLog(String.format("[ERROR] 检测到前置容器 %s 失败 (exit %d): %s",
                                cs.getName(), exitCode, reason));
                        broadcastLog(run);
                        return true;
                    }
                }
            }
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
                run.addLog("[INFO] 等待 BuildKit 容器启动...");
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
     * Stream logs from a specific container in a pod using polling.
     * Handles PodInitializing gracefully by retrying.
     */
    private void streamContainerLogs(KubernetesClient client, String podName, String namespace,
            String containerName, PipelineRun run) {
        try {
            int lastLineCount = 0;
            for (int i = 0; i < 1080; i++) { // up to 90 min: BuildKit 冷缓存首次推 mode=max 全量缓存层到 Harbor 较慢
                Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
                if (pod == null)
                    return;

                String phase = pod.getStatus().getPhase();
                if ("Failed".equals(phase)) {
                    run.addLog("[ERROR] Pod 状态为 Failed");
                    broadcastLog(run);
                    return;
                }

                // Check if the container is running or terminated
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
                                run.addLog(lines[j]);
                            }
                            if (lines.length > lastLineCount) {
                                lastLineCount = lines.length;
                                broadcastLog(run);
                            }
                        }
                    } catch (Exception logErr) {
                        // Transient error, continue polling
                    }
                }

                if (containerTerminated || "Succeeded".equals(phase)) {
                    return;
                }

                Thread.sleep(5000);
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
                    // Try to diagnose buildkit container exit code
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
     * 从已采集的日志中解析合并结果: merge commit sha 与冲突文件列表。
     * git-clone init container 的 stdout 已通过 SSE 进入 run.getLogs()。
     */
    private void parseMergeResults(PipelineRun run) {
        boolean inConflict = false;
        for (String raw : new ArrayList<>(run.getLogs())) {
            // 去掉 addLog 添加的 "[HH:mm:ss] " 时间戳前缀
            String content = raw;
            if (content.startsWith("[") && content.length() > 11 && content.charAt(9) == ']') {
                content = content.substring(11);
            }
            content = content.trim();

            if (content.startsWith("[MERGE] MERGE_COMMIT=")) {
                String sha = content.substring("[MERGE] MERGE_COMMIT=".length()).trim();
                if (!sha.isEmpty() && !sha.startsWith("$")) {
                    run.setMergeCommitSha(sha.length() > 12 ? sha.substring(0, 12) : sha);
                }
                inConflict = false;
            } else if (content.contains("[CONFLICT]")) {
                inConflict = true;
            } else if (inConflict) {
                // 冲突文件列表: git diff --name-only --diff-filter=U 的输出行
                if (content.isEmpty() || content.startsWith("[") || content.startsWith("===")
                        || content.startsWith("CONFLICT")) {
                    inConflict = false;
                } else if (!run.getConflictFiles().contains(content)) {
                    run.addConflictFile(content);
                }
            }
        }
        persist(run, false);
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
            var container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
            container.setImage(fullImage);
            container.setImagePullPolicy("IfNotPresent"); // Force local image usage

            client.apps().deployments().inNamespace(ns).resource(deployment).update();
            run.addLog("[INFO] ✓ Deployment 已更新: " + deployName + " -> " + fullImage);

            // 确保应用有 NodePort Service 可对外访问 (对齐 ReleaseService.ensureService:
            // Deployment 存在但 Service 缺失时补建, 已存在则不动)
            ensureService(client, ns, deployName, config.getEffectiveAppPort(), run);

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
     * 确保 Deployment 有对应的 NodePort Service (对齐 ReleaseService.ensureService)。
     * Deployment 的 label 为 app=&lt;deployName&gt; 时按同名同 selector 补建; 已存在则不动。
     */
    private void ensureService(KubernetesClient client, String namespace, String deployName, int appPort, PipelineRun run) {
        try {
            io.fabric8.kubernetes.api.model.Service existing = client.services().inNamespace(namespace).withName(deployName).get();
            if (existing == null) {
                io.fabric8.kubernetes.api.model.Service svc = new io.fabric8.kubernetes.api.model.ServiceBuilder()
                        .withNewMetadata()
                        .withName(deployName)
                        .withNamespace(namespace)
                        .addToLabels("app", deployName)
                        .endMetadata()
                        .withNewSpec()
                        .withType("NodePort")
                        .addToSelector("app", deployName)
                        .addNewPort()
                        .withName("http")
                        .withProtocol("TCP")
                        .withPort(appPort)
                        .withNewTargetPort(appPort)
                        .endPort()
                        .endSpec()
                        .build();
                client.services().inNamespace(namespace).resource(svc).create();
                io.fabric8.kubernetes.api.model.Service created = client.services().inNamespace(namespace).withName(deployName).get();
                Integer nodePort = created != null && created.getSpec() != null && !created.getSpec().getPorts().isEmpty()
                        ? created.getSpec().getPorts().get(0).getNodePort() : null;
                run.addLog("[INFO] ✓ Service 已创建: " + deployName + " (NodePort: "
                        + (nodePort != null ? nodePort : "自动分配") + ", 访问: http://节点IP:" + (nodePort != null ? nodePort : "NodePort") + ")");
                broadcastLog(run);
            }
        } catch (Exception e) {
            run.addLog("[WARN] Service 创建/检查失败: " + e.getMessage());
            broadcastLog(run);
        }
    }

    /**
     * 合并预览部署: 把构建好的镜像部署到独立预览命名空间 preview-&lt;mergeSetId&gt;。
     * 幂等创建命名空间 + Deployment + NodePort Service(自动分配端口), 并回填访问地址。
     */
    private void deployToPreview(KubernetesClient client, PipelineConfig config, String fullImage, PipelineRun run) {
        try {
            String previewNs = config.getPreviewNamespace();
            run.setPreviewNamespace(previewNs);
            String appName = sanitizeName(config.getImageName()) + "-preview";
            int appPort = config.getEffectiveAppPort();

            // 1. 幂等创建预览命名空间 (带 GC 所需的标签/注解)
            Namespace existingNs = client.namespaces().withName(previewNs).get();
            long nowMs = System.currentTimeMillis();
            if (existingNs == null) {
                Namespace ns = new NamespaceBuilder()
                        .withNewMetadata()
                        .withName(previewNs)
                        .addToLabels("managed-by", "k3s-demo-devops")
                        .addToLabels("preview-env", "true")
                        .addToLabels("merge-set-id", config.getMergeSetId())
                        .addToAnnotations("k3s-demo/created-at", String.valueOf(nowMs))
                        .addToAnnotations("k3s-demo/ttl-minutes", String.valueOf(config.getPreviewTtlMinutes()))
                        .addToAnnotations("k3s-demo/base-branch", config.getEffectiveBaseBranch())
                        .addToAnnotations("k3s-demo/feature-branches",
                                String.join(",", config.getNormalizedFeatureBranches()))
                        .endMetadata()
                        .build();
                client.namespaces().resource(ns).create();
                run.addLog("[INFO] ✓ 已创建预览命名空间: " + previewNs);
            } else {
                run.addLog("[INFO] 复用已存在的预览命名空间: " + previewNs + " (续期 TTL)");
                try {
                    client.namespaces().withName(previewNs).edit(n -> new NamespaceBuilder(n)
                            .editMetadata()
                            .addToAnnotations("k3s-demo/created-at", String.valueOf(nowMs))
                            .endMetadata().build());
                } catch (Exception ignore) {
                }
            }
            broadcastLog(run);

            // 2. 创建/更新 Deployment
            Deployment existing = client.apps().deployments().inNamespace(previewNs).withName(appName).get();
            if (existing == null) {
                Deployment desired = new DeploymentBuilder()
                        .withNewMetadata()
                        .withName(appName)
                        .withNamespace(previewNs)
                        .addToLabels("app", appName)
                        .addToLabels("preview-env", "true")
                        .endMetadata()
                        .withNewSpec()
                        .withReplicas(1)
                        .withNewSelector().addToMatchLabels("app", appName).endSelector()
                        .withNewTemplate()
                        .withNewMetadata().addToLabels("app", appName).endMetadata()
                        .withNewSpec()
                        .addNewContainer()
                        .withName("app")
                        .withImage(fullImage)
                        .withImagePullPolicy("IfNotPresent")
                        .addNewPort().withContainerPort(appPort).endPort()
                        .endContainer()
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build();
                client.apps().deployments().inNamespace(previewNs).resource(desired).create();
                run.addLog("[INFO] ✓ 已创建预览 Deployment: " + appName);
            } else {
                var container = existing.getSpec().getTemplate().getSpec().getContainers().get(0);
                container.setImage(fullImage);
                container.setImagePullPolicy("IfNotPresent");
                client.apps().deployments().inNamespace(previewNs).resource(existing).update();
                run.addLog("[INFO] ✓ 已更新预览 Deployment: " + appName + " -> " + fullImage);
            }
            broadcastLog(run);

            // 3. 创建/更新 NodePort Service (nodePort 自动分配, 避免多预览端口冲突)
            io.fabric8.kubernetes.api.model.Service existingSvc = client.services().inNamespace(previewNs)
                    .withName(appName).get();
            if (existingSvc == null) {
                io.fabric8.kubernetes.api.model.Service svc = new ServiceBuilder()
                        .withNewMetadata()
                        .withName(appName)
                        .withNamespace(previewNs)
                        .addToLabels("app", appName)
                        .addToLabels("preview-env", "true")
                        .endMetadata()
                        .withNewSpec()
                        .withType("NodePort")
                        .addToSelector("app", appName)
                        .addNewPort()
                        .withName("http")
                        .withPort(appPort)
                        .withNewTargetPort(appPort)
                        .withProtocol("TCP")
                        .endPort()
                        .endSpec()
                        .build();
                client.services().inNamespace(previewNs).resource(svc).create();
                run.addLog("[INFO] ✓ 已创建预览 Service (NodePort 自动分配): " + appName);
            } else {
                run.addLog("[INFO] 复用预览 Service: " + appName);
            }
            broadcastLog(run);

            // 4. 读取分配的 nodePort + 节点 IP, 回填访问地址
            io.fabric8.kubernetes.api.model.Service svc = client.services().inNamespace(previewNs)
                    .withName(appName).get();
            Integer nodePort = null;
            if (svc != null && svc.getSpec() != null && svc.getSpec().getPorts() != null
                    && !svc.getSpec().getPorts().isEmpty()) {
                nodePort = svc.getSpec().getPorts().get(0).getNodePort();
            }
            String nodeIp = getFirstNodeIp(client);
            if (nodePort != null && nodeIp != null) {
                String url = "http://" + nodeIp + ":" + nodePort;
                run.setPreviewNodePortUrl(url);
                run.addLog("[INFO] ✓ 预览环境访问地址: " + url);
            } else if (nodePort != null) {
                run.setPreviewNodePortUrl("http://<节点IP>:" + nodePort);
                run.addLog("[INFO] ✓ 预览环境 NodePort: " + nodePort + " (节点IP请用 kubectl get nodes 查看)");
            }
            broadcastLog(run);

            // 5. 等待滚动更新
            Thread.sleep(3000);
            Deployment updated = client.apps().deployments().inNamespace(previewNs).withName(appName).get();
            if (updated != null && updated.getStatus() != null) {
                int desiredR = updated.getSpec().getReplicas() != null ? updated.getSpec().getReplicas() : 1;
                int ready = updated.getStatus().getReadyReplicas() != null ? updated.getStatus().getReadyReplicas() : 0;
                run.addLog("[INFO] 预览副本状态: " + ready + "/" + desiredR + " Ready");
            }
            broadcastLog(run);

        } catch (Exception e) {
            run.addLog("[ERROR] 预览环境部署失败: " + e.getMessage());
            broadcastLog(run);
        }
    }

    /**
     * 把镜像名规范化为合法的 K8s 资源名 (小写 + 仅含 a-z0-9-)。
     */
    private String sanitizeName(String raw) {
        if (raw == null) {
            return "app";
        }
        String s = raw.toLowerCase()
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return s.isEmpty() ? "app" : s;
    }

    /**
     * 取第一个节点的 IP (优先 ExternalIP, 回退 InternalIP)。
     */
    private String getFirstNodeIp(KubernetesClient client) {
        try {
            var nodes = client.nodes().list().getItems();
            for (var node : nodes) {
                if (node.getStatus() != null && node.getStatus().getAddresses() != null) {
                    String external = null;
                    String internal = null;
                    for (var addr : node.getStatus().getAddresses()) {
                        if ("ExternalIP".equals(addr.getType())) {
                            external = addr.getAddress();
                        }
                        if ("InternalIP".equals(addr.getType())) {
                            internal = addr.getAddress();
                        }
                    }
                    if (external != null) {
                        return external;
                    }
                    if (internal != null) {
                        return internal;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 标准 .dockerignore(Python 镜像精简,避免 COPY . . 把 .git/缓存打入)。 */
    private static final String PY_DOCKERIGNORE =
            ".git\n__pycache__/\n*.pyc\n*.pyo\n.venv/\nvenv/\n.pytest_cache/\n.env\n";

    /** 取异常链最深处的根因(类名 + 消息),把笼统的 "An error has occurred." 翻译成可定位的原因。 */
    private static String rootCauseMsg(Throwable t) {
        Throwable c = t;
        int guard = 0;
        while (c.getCause() != null && c.getCause() != c && guard++ < 20) {
            c = c.getCause();
        }
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    /** base64 编码(无换行),用于把生成的 Dockerfile 经 shell 写入 workspace。 */
    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Java 多阶段 Dockerfile(maven 打包 + jre 运行),fromPrefix 为本地 registry/harbor 前缀。 */
    private String buildJavaDockerfile(String fromPrefix, String buildCmd) {
        String settings = "<settings><mirrors><mirror><id>aliyun</id><mirrorOf>*</mirrorOf>"
                + "<url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>";
        // settings.xml 不能放 cache mount(target=/root/.m2 会被覆盖), 写到 /tmp 后用 -s 引用
        return "FROM " + fromPrefix + "/library/maven:3.9-eclipse-temurin-17 AS builder\n"
                + "WORKDIR /build\n"
                + "COPY . .\n"
                // 依赖缓存: cache mount 持久化 /root/.m2, 跨构建复用已下载依赖;
                // 离线 daemonless 模式下该 mount 数据落在 buildkitd 状态目录(已挂 buildkit-cache-pvc)
                + "RUN mkdir -p /tmp/m2 && echo '" + settings + "' > /tmp/m2/settings.xml\n"
                + "RUN --mount=type=cache,target=/root/.m2 mvn -s /tmp/m2/settings.xml -q dependency:go-offline"
                + " || echo '[WARN] go-offline 未完全成功, 构建阶段将按需下载'\n"
                + "RUN --mount=type=cache,target=/root/.m2 mvn -s /tmp/m2/settings.xml " + buildCmd + "\n"
                + "\n"
                + "FROM " + fromPrefix + "/library/eclipse-temurin:17-jre-jammy\n"
                + "WORKDIR /app\n"
                + "COPY --from=builder /build/target/*.jar app.jar\n"
                + "EXPOSE 8080\n"
                + "ENTRYPOINT [\"java\",\"-jar\",\"app.jar\"]\n";
    }

    /** Python 单阶段 Dockerfile(pip install + gunicorn/uvicorn),见 PYTHON-RELEASE-DESIGN.md 第五节。 */
    private String buildPythonDockerfile(String fromPrefix, PipelineConfig config) {
        int port = config.getEffectiveAppPort();
        String pyver = (config.getPythonVersion() != null && !config.getPythonVersion().isEmpty())
                ? config.getPythonVersion() : "3.11";
        String server = "uvicorn".equals(config.getPythonServer()) ? "uvicorn" : "gunicorn";
        String module = (config.getAppModule() != null && !config.getAppModule().isEmpty())
                ? config.getAppModule() : "app:app";
        String rp = (config.getRequirementsPath() != null && !config.getRequirementsPath().isEmpty())
                ? config.getRequirementsPath() : "requirements.txt";
        StringBuilder df = new StringBuilder();
        df.append("FROM ").append(fromPrefix).append("/library/python:").append(pyver).append("-slim\n");
        df.append("WORKDIR /app\n");
        if (config.isBuildDeps()) {
            df.append("RUN apt-get update && apt-get install -y --no-install-recommends build-essential gcc"
                    + " && rm -rf /var/lib/apt/lists/*\n");
        }
        df.append("COPY ").append(rp).append(" ./requirements.txt\n");
        df.append("RUN pip install --no-cache-dir -i ").append(pipIndexUrl).append(" -r requirements.txt\n");
        df.append("RUN pip install --no-cache-dir -i ").append(pipIndexUrl).append(" ").append(server).append("\n");
        df.append("COPY . .\n");
        df.append("EXPOSE ").append(port).append("\n");
        String sc = config.getStartCommand();
        if (sc != null && !sc.trim().isEmpty()) {
            df.append("CMD [\"sh\", \"-c\", \"").append(jsonEscape(sc.trim())).append("\"]\n");
        } else if ("uvicorn".equals(server)) {
            df.append("CMD [\"uvicorn\", \"").append(module)
                    .append("\", \"--host\", \"0.0.0.0\", \"--port\", \"").append(port).append("\"]\n");
        } else {
            df.append("CMD [\"gunicorn\", \"-b\", \"0.0.0.0:").append(port).append("\", \"")
                    .append(module).append("\"]\n");
        }
        return df.toString();
    }

    /**
     * Build the BuildKit Job spec.
     * Init containers: registry-check → git-clone → rewrite-dockerfile → buildkit
     * Main container: loader (import tar to K3s containerd)
     * Maven 打包通过多阶段 Dockerfile 在 BuildKit 内完成，不再需要单独的 maven-build 容器。
     */
    private Job buildBuildkitJob(String jobName, String pipelineId, PipelineConfig config, String fullImage) {
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

        // 构建 clone 命令: 代理 + 超时 (git 已预装在 git-alpine 工具镜像内, 无需运行时安装包)
        StringBuilder cloneCmdBuilder = new StringBuilder();
        cloneCmdBuilder.append("git config --global http.version HTTP/1.1 && ");
        cloneCmdBuilder.append("git config --global protocol.version 1 && ");
        cloneCmdBuilder.append("git config --global http.postBuffer 524288000 && ");
        cloneCmdBuilder.append("git config --global http.lowSpeedLimit 1000 && ");
        cloneCmdBuilder.append("git config --global http.lowSpeedTime 120 && ");
        cloneCmdBuilder.append("git config --global core.compression 0 && ");
        if (config.hasGitProxy()) {
            cloneCmdBuilder.append("git config --global http.proxy ").append(config.getGitProxy()).append(" && ");
            cloneCmdBuilder.append("git config --global https.proxy ").append(config.getGitProxy()).append(" && ");
            cloneCmdBuilder.append("echo '[INFO] 已配置 Git 代理: ").append(config.getGitProxy()).append("' && ");
        }
        // ===== 增量 clone: 同仓库 workspace 挂在按 gitUrl 哈希派生的 PVC 上 (见 buildBuildkitJob Volumes) =====
        // 单分支模式: 浅克隆 + 浅增量 (ghproxy 大包传输不稳定, 全量 pack 必断; --depth 1 快照小一个数量级)
        String branch = config.getBranch();
        String ensureRepoShallow = String.format(
                "if [ -d /workspace/.git ]; then "
                        + "echo '[INFO] 检测到已有工作区缓存, 浅增量更新 (fetch --depth 1 + reset)...' && "
                        + "cd /workspace && "
                        + "git remote set-url origin %s && "
                        + "git fetch --depth 1 origin %s && "
                        + "git checkout -f %s && "
                        + "git reset --hard FETCH_HEAD && "
                        + "git clean -fd; "
                        + "else git clone --depth 1 --branch %s %s /workspace && cd /workspace; fi && ",
                cloneUrl, branch, branch, branch, cloneUrl);
        if (config.isMergeDeploy()) {
            // ===== 多分支合并模式: clone base + 逐个 merge feature, 冲突即中止 =====
            // 合并需要完整历史做 merge, 不能用 --depth 1; 保留全量 clone + fetch
            String base = config.getEffectiveBaseBranch();
            String ensureRepo = String.format(
                    "if [ -d /workspace/.git ]; then "
                            + "echo '[INFO] 检测到已有工作区缓存, 增量更新 (fetch + reset)...' && "
                            + "cd /workspace && "
                            + "git remote set-url origin %s && "
                            + "git fetch --prune origin && "
                            + "git checkout -f %s && "
                            + "git reset --hard origin/%s && "
                            + "git clean -fd; "
                            + "else git clone --branch %s %s /workspace && cd /workspace; fi && ",
                    cloneUrl, base, base, base, cloneUrl);
            cloneCmdBuilder.append(ensureRepo);
            cloneCmdBuilder.append("git config user.email 'ci@k3s-demo.local' && ");
            cloneCmdBuilder.append("git config user.name 'k3s-demo-ci' && ");
            cloneCmdBuilder.append(String.format("echo '[MERGE] 基底分支(base): %s' && ", base));
            for (String feat : config.getNormalizedFeatureBranches()) {
                cloneCmdBuilder.append(String.format("echo '[MERGE] ➜ 合并分支: %s' && ", feat));
                cloneCmdBuilder.append(String.format("git fetch origin %s && ", feat));
                // merge --no-ff; 冲突时: 打印冲突文件 + abort + exit 1 (Job backoffLimit=0 → 直接失败)
                cloneCmdBuilder.append(String.format(
                        "{ git merge --no-ff --no-edit -m 'ci: merge %s into %s' FETCH_HEAD || "
                                + "{ echo '[CONFLICT] 分支 %s 与当前集成分支存在合并冲突, 冲突文件如下:'; "
                                + "git diff --name-only --diff-filter=U; "
                                + "git merge --abort; exit 1; }; } && ",
                        feat, base, feat));
                cloneCmdBuilder.append(String.format("echo '[MERGE] ✓ 已合并: %s' && ", feat));
            }
            cloneCmdBuilder.append("echo \"[MERGE] MERGE_COMMIT=$(git rev-parse HEAD)\" && ");
            cloneCmdBuilder.append("echo '=== 合并完成，文件列表: ===' && ls -la /workspace");
        } else {
            // ===== 单分支模式: 浅克隆 + 浅增量 =====
            cloneCmdBuilder.append(ensureRepoShallow);
            cloneCmdBuilder.append("echo '[INFO] Clone completed successfully' && "
                    + "echo '=== 下载成功，文件列表: ===' && "
                    + "ls -la /workspace");
        }
        String cloneCommand = cloneCmdBuilder.toString();

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
                .withHostNetwork(true) // 允许访问宿主机 localhost:5000
                .withDnsPolicy("ClusterFirstWithHostNet")
                .withRestartPolicy("Never")
                // Init container 0: Registry check
                .addNewInitContainer()
                .withName("registry-check")
                .withImage(gitImage) // Use gitImage (alpine)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("sh", "-c", "echo '[INFO] Checking local registry...' && " +
                        "curl -f -v --connect-timeout 5 http://" + localRegistry + "/v2/ && " +
                        "echo '[INFO] Local registry is reachable'")
                .endInitContainer()
                // Init container 1: Git clone
                .addNewInitContainer()
                .withName("git-clone")
                .withImage(gitImage)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("sh", "-c", cloneCommand)
                .addNewVolumeMount()
                .withName("workspace")
                .withMountPath("/workspace")
                .endVolumeMount()
                .endInitContainer();

        // Init container 2: 智能 Dockerfile 处理 (runtime 感知)
        // 优先级: 仓库自带 Dockerfile → 重写 FROM 后使用;否则按 runtime 生成
        //   - java   : 多阶段 (maven 打包 + jre 运行)
        //   - python : 单阶段 (pip install + gunicorn/uvicorn)
        //   - auto   : 探测 requirements.txt→python / pom.xml→java / pyproject→报错
        String dockerfilePath = config.getDockerfilePath();
        String dfFile = dockerfilePath.startsWith("./") ? dockerfilePath.substring(2) : dockerfilePath;
        String buildCmd = config.hasBuildStep() ? config.getBuildCommand() : "mvn clean package -DskipTests";
        String runtime = config.resolveRuntime();
        String reqPath = (config.getRequirementsPath() != null && !config.getRequirementsPath().isEmpty())
                ? config.getRequirementsPath() : "requirements.txt";

        // 生成 java / python Dockerfile 内容并 base64 编码(避免 shell 转义),容器按 runtime 写入
        String javaDfB64 = b64(buildJavaDockerfile(localRegistry, buildCmd));
        String pyDfB64 = b64(buildPythonDockerfile(localRegistry, config));
        String dockerIgnoreB64 = b64(PY_DOCKERIGNORE);

        String rewriteCmd =
                "REGISTRY='" + localRegistry + "' && " +
                        "DF='/workspace/" + dfFile + "' && " +
                        "RUNTIME='" + runtime + "' && " +
                        "REQ_PATH='" + reqPath + "' && " +
                        "if [ -f \"$DF\" ]; then " +
                        "  echo '[INFO] 检测到仓库自带 Dockerfile, 优先使用 (重写 FROM 到本地 Registry)' && " +
                        "  sed -i 's|^FROM docker\\.io/|FROM '\"$REGISTRY\"'/|; s|^FROM library/|FROM '\"$REGISTRY\"'/library/|' \"$DF\" && " +
                        "  sed -i '/^FROM [^/]*$/s|^FROM |FROM '\"$REGISTRY\"'/library/|' \"$DF\" && " +
                        "  echo '[INFO] 重写后 Dockerfile:' && cat \"$DF\"; " +
                        "else " +
                        "  if [ \"$RUNTIME\" = 'auto' ] || [ -z \"$RUNTIME\" ]; then " +
                        "    HAS_PY=0; HAS_JAVA=0; " +
                        "    if [ -f \"/workspace/$REQ_PATH\" ] || [ -f /workspace/requirements.txt ]; then HAS_PY=1; fi; " +
                        "    if [ -f /workspace/pom.xml ] || [ -f /workspace/build.gradle ]; then HAS_JAVA=1; fi; " +
                        "    if [ \"$HAS_PY\" = 1 ] && [ \"$HAS_JAVA\" = 1 ]; then " +
                        "      echo '[ERROR] 同时检测到 Java(pom.xml/build.gradle) 与 Python(requirements.txt) 构建文件, 无法自动判定语言; 请在表单显式选择 runtime=java 或 python(避免 Python/Java 流水线混淆)' && exit 1; " +
                        "    elif [ \"$HAS_PY\" = 1 ]; then RUNTIME=python; " +
                        "    elif [ \"$HAS_JAVA\" = 1 ]; then RUNTIME=java; " +
                        "    elif [ -f /workspace/pyproject.toml ] || [ -f /workspace/Pipfile ]; then " +
                        "      echo '[ERROR] 检测到 pyproject/Pipfile 但缺少 requirements.txt; 本期未支持 Poetry/Pipenv, 请先 poetry export -f requirements.txt -o requirements.txt 或自带 Dockerfile' && exit 1; " +
                        "    else echo '[ERROR] 无法识别项目类型, 请显式指定 runtime(java/python) 或自带 Dockerfile' && exit 1; fi; " +
                        "  fi; " +
                        "  if [ \"$RUNTIME\" = 'dockerfile' ]; then echo '[ERROR] runtime=dockerfile 但仓库未发现 Dockerfile' && exit 1; fi; " +
                        "  echo \"[INFO] 构建运行时: $RUNTIME\"; " +
                        "  if [ \"$RUNTIME\" = 'python' ]; then " +
                        "    if [ ! -f \"/workspace/$REQ_PATH\" ]; then echo '# auto-created empty requirements' > \"/workspace/$REQ_PATH\" && echo \"[INFO] 未发现 $REQ_PATH, 使用空依赖清单\"; fi && " +
                        "    echo '" + pyDfB64 + "' | base64 -d > \"$DF\" && " +
                        "    echo '" + dockerIgnoreB64 + "' | base64 -d > /workspace/.dockerignore && " +
                        "    echo '[INFO] ✓ 已生成 Python Dockerfile:' && cat \"$DF\"; " +
                        "  else " +
                        "    echo '[INFO] ✓ 生成 Java 多阶段 Dockerfile (Maven 打包 + 镜像构建)' && " +
                        "    echo '" + javaDfB64 + "' | base64 -d > \"$DF\" && cat \"$DF\"; " +
                        "  fi; " +
                        "fi";
        jobBuilder = jobBuilder
                .addNewInitContainer()
                .withName("rewrite-dockerfile")
                .withImage(gitImage)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("sh", "-c", rewriteCmd)
                .addNewVolumeMount()
                .withName("workspace")
                .withMountPath("/workspace")
                .endVolumeMount()
                .endInitContainer();

        // Init container 3: BuildKit (多阶段构建 → tar, 离线模式)
        // rootless daemonless 模式: buildctl-daemonless.sh 单容器起临时 daemon 构建,
        // 产出 docker 格式 tar 供 loader 容器 ctr import 到 containerd
        String cacheRef = harborHost + "/" + harborProject + "/buildkit-cache";
        String buildkitCmd = "set -e; "
                + "mkdir -p $HOME/.docker $HOME/.config/buildkit; "
                + "mkdir -p $HOME/.local/share/buildkit; "
                + "if [ -d $HOME/.local/share/buildkit/blobs ]; then "
                + "  echo '[INFO] BuildKit 缓存目录已存在, 基础镜像层将复用 (跳过重新拉取)'; "
                + "else "
                + "  echo '[INFO] BuildKit 缓存目录为空, 首次构建将拉取基础镜像层'; "
                + "fi; "
                + "cp /docker-config/config.json $HOME/.docker/config.json 2>/dev/null || true; "
                + "printf '[registry.\"%s\"]\\n  http = true\\n  insecure = true\\n' \"" + harborHost
                + "\" > $HOME/.config/buildkit/buildkitd.toml; "
                + "printf '[registry.\"%s\"]\\n  http = true\\n  insecure = true\\n' \"" + localRegistry
                + "\" >> $HOME/.config/buildkit/buildkitd.toml; "
                + "exec buildctl-daemonless.sh build"
                + " --frontend dockerfile.v0"
                + " --local context=/workspace"
                + " --local dockerfile=/workspace"
                + " --opt filename=" + config.getDockerfilePath()
                + " --opt build-arg:PIP_INDEX_URL=" + pipIndexUrl
                + " --import-cache type=registry,ref=" + cacheRef
                + " --export-cache type=registry,ref=" + cacheRef + ",mode=max"
                + " --output type=docker,name=" + fullImage + ",dest=/workspace/image.tar"
                + " --progress=plain";
        jobBuilder = jobBuilder
                .addNewInitContainer()
                .withName("buildkit")
                .withImage(buildkitImage)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/bin/sh", "-c")
                .withArgs(buildkitCmd)
                .addNewEnv()
                .withName("BUILDKITD_FLAGS")
                .withValue("--oci-worker-no-process-sandbox")
                .endEnv()
                .withNewSecurityContext()
                .withRunAsUser(1000L)
                .withRunAsGroup(1000L)
                .withRunAsNonRoot(true)
                .withNewSeccompProfile()
                .withType("Unconfined")
                .endSeccompProfile()
                .endSecurityContext()
                .addNewVolumeMount()
                .withName("docker-config")
                .withMountPath("/docker-config")
                .endVolumeMount()
                .withNewResources()
                .addToRequests("cpu", new Quantity("500m"))
                .addToRequests("memory", new Quantity("1Gi"))
                .addToLimits("cpu", new Quantity("2"))
                .addToLimits("memory", new Quantity(buildkitMemoryLimit))
                .endResources()
                .addNewVolumeMount()
                .withName("workspace")
                .withMountPath("/workspace")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("buildkit-state")
                .withMountPath("/home/user/.local/share/buildkit")
                .endVolumeMount()
                .endInitContainer();

        // ... (existing code)

        // Main container: Image Loader (imports tar to K3s)
        return jobBuilder
                .addNewContainer()
                .withName("loader")
                .withImage(loaderImage)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("ctr", "-a", "/run/k3s/containerd/containerd.sock", "-n", "k8s.io", "images", "import",
                        "/workspace/image.tar")
                .withNewSecurityContext()
                .withPrivileged(true)
                .endSecurityContext()
                .addNewVolumeMount()
                .withName("workspace")
                .withMountPath("/workspace")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("k3s-sock")
                .withMountPath("/run/k3s/containerd/containerd.sock")
                .endVolumeMount()
                .endContainer()
                // Volumes
                .addNewVolume()
                .withName("docker-config")
                .withNewSecret()
                .withSecretName("harbor-registry-secret")
                .withOptional(true)
                .addNewItem()
                .withKey(".dockerconfigjson")
                .withPath("config.json")
                .endItem()
                .endSecret()
                .endVolume()
                .addNewVolume()
                .withName("workspace")
                .withNewPersistentVolumeClaim()
                .withClaimName(workspacePvcName(config.getGitUrl()))
                .endPersistentVolumeClaim()
                .endVolume()
                .addNewVolume()
                .withName("buildkit-state")
                .withNewPersistentVolumeClaim()
                .withClaimName(BUILDKIT_CACHE_PVC_NAME)
                .endPersistentVolumeClaim()
                .endVolume()
                .addNewVolume()
                .withName("k3s-sock")
                .withNewHostPath()
                .withPath("/run/k3s/containerd/containerd.sock")
                .endHostPath()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    /** 确保 buildkitd 状态缓存 PVC 存在 (不存在则创建), 供跨 Job 复用基础镜像层。 */
    public static void ensureBuildkitCachePvc(KubernetesClient client) {
        if (client.persistentVolumeClaims().inNamespace("default").withName(BUILDKIT_CACHE_PVC_NAME).get() != null) {
            return; // 已存在
        }
        try {
            client.persistentVolumeClaims().inNamespace("default").resource(buildBuildkitCachePvc()).create();
        } catch (KubernetesClientException conflict) {
            if (conflict.getCode() != 409) {
                throw conflict; // 并发创建撞名则视为已存在
            }
        }
    }

    /** 构造 buildkit 缓存 PVC 对象 (10Gi / RWO / default ns)。提取为纯函数便于测试。 */
    public static PersistentVolumeClaim buildBuildkitCachePvc() {
        return new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName(BUILDKIT_CACHE_PVC_NAME).withNamespace("default").endMetadata()
                .withNewSpec()
                .withAccessModes("ReadWriteOnce")
                .withNewResources()
                .addToRequests("storage", new Quantity("10Gi"))
                .endResources()
                .endSpec()
                .build();
    }

    /** 按 gitUrl 派生 workspace PVC 名: 同一仓库多次构建复用同一 PVC, 实现增量 clone。 */
    public static String workspacePvcName(String gitUrl) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest((gitUrl == null ? "" : gitUrl).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return "workspace-pvc-" + sb;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 确保 workspace PVC 存在 (不存在则创建, 已存在则跳过), 供增量 clone 复用。 */
    public static void ensureWorkspacePvc(KubernetesClient client, String pvcName) {
        // fabric8 的 get() 在资源不存在时返回 null (不是抛 404), 必须用 null 判断"需创建"
        if (client.persistentVolumeClaims().inNamespace("default").withName(pvcName).get() != null) {
            return; // 已存在
        }
        try {
            client.persistentVolumeClaims().inNamespace("default").resource(buildWorkspacePvc(pvcName)).create();
        } catch (KubernetesClientException conflict) {
            if (conflict.getCode() != 409) {
                throw conflict; // 并发创建撞名则视为已存在
            }
        }
    }

    /** 构造 workspace PVC 对象 (5Gi / RWO / default ns)。提取为纯函数便于测试。 */
    public static PersistentVolumeClaim buildWorkspacePvc(String pvcName) {
        return new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName(pvcName).withNamespace("default").endMetadata()
                .withNewSpec()
                .withAccessModes("ReadWriteOnce")
                .withNewResources()
                .addToRequests("storage", new Quantity("5Gi"))
                .endResources()
                .endSpec()
                .build();
    }

    /**
     * Cleanup completed BuildKit Job.
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
     * ── Layer 4: Diagnose Job/Pod failure (Detailed) ──
     */
    private void diagnoseMainContainerFailure(KubernetesClient client, String jobName, PipelineRun run) {
        try {
            List<Pod> pods = client.pods().inNamespace("default")
                    .withLabel("job-name", jobName).list().getItems();
            if (pods.isEmpty()) {
                run.addLog("[ERROR] 找不到 Job 对应的 Pod: " + jobName);
                broadcastLog(run);
                return;
            }
            Pod pod = pods.get(0);
            String podName = pod.getMetadata().getName();

            run.addLog("=== 错误诊断报告 (" + podName + ") ===");

            // 1. Events
            String events = parsePodEvents(client, podName);
            if (events != null && !events.isEmpty()) {
                run.addLog("📋 Pod 事件:\n" + events);
            }

            // 2. Init Containers
            var initStatuses = pod.getStatus().getInitContainerStatuses();
            if (initStatuses != null) {
                for (var cs : initStatuses) {
                    diagnoseContainerStatus(client, podName, cs, run);
                }
            }

            // 3. Main Containers
            var statuses = pod.getStatus().getContainerStatuses();
            if (statuses != null) {
                for (var cs : statuses) {
                    diagnoseContainerStatus(client, podName, cs, run);
                }
            }

            // 4. Pod Conditions
            String condition = parsePodConditions(pod);
            if (condition != null) {
                run.addLog("⚠️ Pod 状态条件异常: " + condition);
            }

            broadcastLog(run);
        } catch (Exception e) {
            run.addLog("[ERROR] 诊断失败: " + e.getMessage());
            broadcastLog(run);
        }
    }

    private void diagnoseContainerStatus(KubernetesClient client, String podName, ContainerStatus cs, PipelineRun run) {
        if (cs.getState() != null) {
            var state = cs.getState();
            if (state.getTerminated() != null) {
                var term = state.getTerminated();
                int exitCode = term.getExitCode();
                if (exitCode != 0) {
                    run.addLog(String.format("❌ 容器 [%s] 失败 (exit code %d): %s",
                            cs.getName(), exitCode, term.getReason()));
                    if (term.getMessage() != null) {
                        run.addLog("   消息: " + term.getMessage());
                    }
                    run.addLog("   建议: " + diagnoseExitCode(exitCode, term.getReason()));

                    // Fetch logs for failed container
                    try {
                        String logs = client.pods().inNamespace("default").withName(podName)
                                .inContainer(cs.getName()).tailingLines(20).getLog();
                        if (logs != null && !logs.isEmpty()) {
                            run.addLog("🔍 容器 [" + cs.getName() + "] 错误日志 (Last 20 lines):\n" + logs);
                        } else {
                            run.addLog("🔍 容器 [" + cs.getName() + "] 无日志输出");
                        }
                    } catch (Exception e) {
                        run.addLog("   (无法获取容器日志: " + e.getMessage() + ")");
                    }
                }
            } else if (state.getWaiting() != null) {
                var wait = state.getWaiting();
                String reason = wait.getReason();
                if (!"PodInitializing".equals(reason) && !"ContainerCreating".equals(reason)) {
                    run.addLog(String.format("⚠️ 容器 [%s] 异常等待: %s",
                            cs.getName(), reason));
                    if (wait.getMessage() != null) {
                        run.addLog("   消息: " + wait.getMessage());
                    }
                }
            }
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
        LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(90);
        for (Map.Entry<String, PipelineRun> entry : pipelineRuns.entrySet()) {
            PipelineRun run = entry.getValue();
            if (!run.isFinished() && run.getLastActivityTime().isBefore(cutoff)) {
                run.addLog("[WARN] 流水线超过 90 分钟无活动, 强制终止");
                run.fail("超时被系统强制终止 (90分钟无活动)");
                broadcastStatus(run);
                broadcastLog(run);
                completeEmitters(run.getId());
                // Try to cleanup the K3s Job
                String jobName = "buildkit-" + run.getId();
                try (KubernetesClient client = new KubernetesClientBuilder().build()) {
                    cleanupJob(client, jobName);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ========== 预览环境 (Preview Environment) 管理 ==========

    /**
     * 列出当前所有合并预览环境 (label preview-env=true 的命名空间)。
     */
    public List<Map<String, Object>> listPreviewEnvironments() {
        List<Map<String, Object>> result = new ArrayList<>();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            var nsList = client.namespaces().withLabel("preview-env", "true").list().getItems();
            long now = System.currentTimeMillis();
            for (var ns : nsList) {
                Map<String, Object> m = new LinkedHashMap<>();
                String name = ns.getMetadata().getName();
                m.put("namespace", name);
                m.put("mergeSetId", name.startsWith("preview-") ? name.substring("preview-".length()) : name);
                Map<String, String> ann = ns.getMetadata().getAnnotations() != null
                        ? ns.getMetadata().getAnnotations()
                        : Collections.emptyMap();
                m.put("baseBranch", ann.getOrDefault("k3s-demo/base-branch", ""));
                m.put("featureBranches", ann.getOrDefault("k3s-demo/feature-branches", ""));
                long createdAt = parseLong(ann.get("k3s-demo/created-at"), 0L);
                int ttl = (int) parseLong(ann.get("k3s-demo/ttl-minutes"), 120);
                long ageMin = createdAt > 0 ? (now - createdAt) / 60000 : -1;
                m.put("createdAt", createdAt);
                m.put("ttlMinutes", ttl);
                m.put("ageMinutes", ageMin);
                m.put("ttlRemainingMinutes", createdAt > 0 ? Math.max(0, ttl - ageMin) : -1);
                m.put("phase", ns.getStatus() != null ? ns.getStatus().getPhase() : "");
                // Service NodePort + 访问地址
                try {
                    var svcs = client.services().inNamespace(name)
                            .withLabel("preview-env", "true").list().getItems();
                    if (!svcs.isEmpty() && svcs.get(0).getSpec().getPorts() != null
                            && !svcs.get(0).getSpec().getPorts().isEmpty()) {
                        Integer np = svcs.get(0).getSpec().getPorts().get(0).getNodePort();
                        if (np != null) {
                            String ip = getFirstNodeIp(client);
                            m.put("nodePort", np);
                            m.put("url", (ip != null ? "http://" + ip : "http://<节点IP>") + ":" + np);
                        }
                    }
                } catch (Exception ignored) {
                }
                result.add(m);
            }
        } catch (Exception e) {
            // 集群不可达时返回空列表
        }
        return result;
    }

    /**
     * 销毁指定预览环境 (删除整个 preview-&lt;id&gt; 命名空间)。
     * 仅允许删除带 preview-env=true 标签的受管命名空间, 避免误删。
     */
    public boolean destroyPreviewEnvironment(String id) {
        String ns = id.startsWith("preview-") ? id : "preview-" + id;
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            var existing = client.namespaces().withName(ns).get();
            if (existing == null) {
                return false;
            }
            var labels = existing.getMetadata().getLabels();
            if (labels == null || !"true".equals(labels.get("preview-env"))) {
                return false; // 非受管命名空间, 拒绝删除
            }
            client.namespaces().withName(ns)
                    .withPropagationPolicy(DeletionPropagation.BACKGROUND).delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ── 定时回收超过 TTL 的预览环境 ── (仿 sweepStalePipelines)
     */
    @Scheduled(fixedRate = 300000) // 每 5 分钟
    public void sweepStalePreviewEnvs() {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            var nsList = client.namespaces().withLabel("preview-env", "true").list().getItems();
            long now = System.currentTimeMillis();
            for (var ns : nsList) {
                var ann = ns.getMetadata().getAnnotations();
                if (ann == null) {
                    continue;
                }
                long createdAt = parseLong(ann.get("k3s-demo/created-at"), 0L);
                int ttl = (int) parseLong(ann.get("k3s-demo/ttl-minutes"), 120);
                if (createdAt > 0 && ttl > 0) {
                    long ageMin = (now - createdAt) / 60000;
                    if (ageMin > ttl) {
                        try {
                            client.namespaces().withName(ns.getMetadata().getName())
                                    .withPropagationPolicy(DeletionPropagation.BACKGROUND).delete();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private long parseLong(String s, long def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
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
        persist(run, false);

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
        persist(run, false);

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
        PipelineRun run = pipelineRuns.get(pipelineId);
        if (run != null) {
            persist(run, true);
        }

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

    /**
     * 把流水线当前状态同步到数据库（不抛异常：DB 故障不影响流水线执行）。
     */
    private void persist(PipelineRun run, boolean completed) {
        if (persistence == null) {
            return;
        }
        try {
            if (completed) {
                persistence.onCompleted(run);
            } else {
                persistence.onChanged(run);
            }
        } catch (Exception e) {
            System.err.println("[DevOpsService] 流水线状态落库失败: " + e.getMessage());
        }
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

    /**
     * 列出远程仓库的分支 (git ls-remote --heads), 供 UI 多选下拉。
     * 使用 JGit 纯 Java 实现, 运行时镜像无需 git 二进制。
     *
     * @param gitUrl 仓库地址 (https://...)
     * @param token  可选鉴权 token; 为空时回退全局 gitlab.token
     */
    public List<String> listRemoteBranches(String gitUrl, String token) {
        List<String> branches = new ArrayList<>();
        if (gitUrl == null || gitUrl.isEmpty()) {
            return branches;
        }
        String effToken = (token != null && !token.isEmpty()) ? token : globalGitlabToken;
        try {
            var cmd = org.eclipse.jgit.api.Git.lsRemoteRepository()
                    .setRemote(gitUrl)
                    .setHeads(true)
                    .setTags(false);
            if (effToken != null && !effToken.isEmpty()) {
                cmd.setCredentialsProvider(
                        new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider("oauth2", effToken));
            }
            var refs = cmd.call();
            for (var ref : refs) {
                String name = ref.getName();
                if (name != null && name.startsWith("refs/heads/")) {
                    branches.add(name.substring("refs/heads/".length()));
                }
            }
            branches.sort(String::compareTo);
        } catch (Exception e) {
            throw new RuntimeException("无法获取分支列表: " + e.getMessage(), e);
        }
        return branches;
    }
}
