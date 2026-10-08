package com.example.k3sdemo.delivery.service;

import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.entity.Artifact;
import com.example.k3sdemo.delivery.entity.Release;
import com.example.k3sdemo.delivery.repository.ApplicationRepository;
import com.example.k3sdemo.delivery.repository.ArtifactRepository;
import com.example.k3sdemo.delivery.repository.ReleaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 发布执行服务（P3 桥接）：把交付中心的 Release 接到真实流水线。
 * 流程：execute() 触发 PipelineTrigger → 异步轮询 → 阶段映射回写 release_stage。
 * 阶段映射（6 阶段 → 流水线状态）：
 *   1 PR_CHECK  ← 触发成功即视为通过（PR 门禁由 Git 平台承担，本平台不重复）
 *   2 BUILD     ← CLONING/MERGING/BUILDING
 *   3 PREVIEW   ← 非生产环境发布：PUSHING+DEPLOYING 即本环境部署
 *   4 BETA      ← 略（SKIPPED，单环境直部署）
 *   5 CANARY    ← PROD 环境：PUSHING/DEPLOYING（现有引擎为滚动更新，非真灰度）
 *   6 STABLE    ← SUCCESS
 */
@Service
public class ReleaseExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseExecutionService.class);

    private final ReleaseRepository releaseRepository;
    private final ReleasePersistenceService persistenceService;
    private final ApplicationRepository applicationRepository;
    private final ArtifactRepository artifactRepository;
    private final PipelineTrigger pipelineTrigger;
    private final ActivityLogService activityLog;
    private final ExecutorService pollExecutor = Executors.newFixedThreadPool(2);

    /** releaseId → pipelineId，供页面关联流水线日志 */
    private final Map<Long, String> activePipelines = new ConcurrentHashMap<>();

    public ReleaseExecutionService(ReleaseRepository releaseRepository,
                                   ReleasePersistenceService persistenceService,
                                   ApplicationRepository applicationRepository,
                                   ArtifactRepository artifactRepository,
                                   PipelineTrigger pipelineTrigger,
                                   ActivityLogService activityLog) {
        this.releaseRepository = releaseRepository;
        this.persistenceService = persistenceService;
        this.applicationRepository = applicationRepository;
        this.artifactRepository = artifactRepository;
        this.pipelineTrigger = pipelineTrigger;
        this.activityLog = activityLog;
    }

    /** 触发执行：校验状态并启动流水线，立即返回。 */
    public Release execute(Long releaseId) {
        Release release = releaseRepository.findById(releaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "发布不存在: " + releaseId));
        if (release.getStatus() != null
                && !Release.Status.PENDING.name().equals(release.getStatus())
                && !Release.Status.FAILED.name().equals(release.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "发布当前状态为 " + release.getStatus() + "，仅待启动或失败的发布可执行");
        }
        if (activePipelines.containsKey(releaseId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该发布已有流水线在执行中");
        }
        Artifact artifact = artifactRepository.findById(release.getArtifactId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "制品不存在: " + release.getArtifactId()));
        // 应用信息从制品仓库名推导镜像名（Harbor project/app 形式）
        String imageName = artifact.getImageRepo();
        String deployment = "app-" + release.getAppId();
        String namespace = "default";

        String pipelineId;
        try {
            pipelineId = pipelineTrigger.trigger(gitUrlFor(release),
                    branchFor(artifact), imageName, deployment, namespace);
        } catch (PipelineTrigger.TriggerException e) {
            activityLog.log(release.getAppId(), releaseId, "SYSTEM", null,
                    "EXECUTE_FAILED", "触发流水线失败: " + e.getMessage(), release.getEnv());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "触发流水线失败: " + e.getMessage());
        }

        activePipelines.put(releaseId, pipelineId);
        release.setCurrentStage("PR_CHECK");
        releaseRepository.save(release);
        persistenceService.advanceStage(releaseId, 1, "PASSED");  // PR 检查由 Git 平台承担
        persistenceService.advanceStage(releaseId, 2, "RUNNING"); // 构建开始
        activityLog.log(release.getAppId(), releaseId, "SYSTEM", null,
                "EXECUTE_STARTED", "流水线已启动 (pipeline=" + pipelineId + ")", release.getEnv());

        pollExecutor.submit(() -> watchPipeline(releaseId, pipelineId));
        return releaseRepository.findById(releaseId).orElse(release);
    }

    /** 异步轮询流水线状态并推进阶段。 */
    private void watchPipeline(Long releaseId, String pipelineId) {
        try {
            String lastStatus = null;
            int deployStage = -1; // 待进入的部署阶段序号
            while (!Thread.currentThread().isInterrupted()) {
                Map<String, Object> status = pipelineTrigger.poll(pipelineId);
                String st = String.valueOf(status.get("status"));
                boolean finished = Boolean.TRUE.equals(status.get("finished"));
                if (!st.equals(lastStatus)) {
                    log.info("release {} pipeline {} -> {}", releaseId, pipelineId, st);
                    lastStatus = st;
                }
                switch (st) {
                    case "PUSHING", "DEPLOYING" -> {
                        if (deployStage < 0) {
                            Release release = releaseRepository.findById(releaseId).orElse(null);
                            if (release == null) return;
                            deployStage = "PROD".equals(release.getEnv()) ? 5 : 3;
                            persistenceService.advanceStage(releaseId, 2, "PASSED");
                            persistenceService.advanceStage(releaseId, deployStage, "RUNNING");
                            if (deployStage == 5) {
                                persistenceService.advanceStage(releaseId, 4, "SKIPPED");
                            }
                        }
                    }
                    case "SUCCESS" -> {
                        Release rel = releaseRepository.findById(releaseId).orElse(null);
                        if (rel == null) return;
                        int finalDeployStage = deployStage > 0 ? deployStage
                                : ("PROD".equals(rel.getEnv()) ? 5 : 3);
                        if (deployStage < 0) {
                            // 流水线未经 PUSHING/DEPLOYING 直接 SUCCESS：补齐部署阶段
                            persistenceService.advanceStage(releaseId, 2, "PASSED");
                            persistenceService.advanceStage(releaseId, finalDeployStage, "PASSED");
                        } else {
                            persistenceService.advanceStage(releaseId, deployStage, "PASSED");
                        }
                        if ("PROD".equals(rel.getEnv())) {
                            persistenceService.advanceStage(releaseId, 3, "SKIPPED");
                            persistenceService.advanceStage(releaseId, 4, "SKIPPED");
                        } else {
                            persistenceService.advanceStage(releaseId, 5, "SKIPPED");
                        }
                        persistenceService.advanceStage(releaseId, 6, "PASSED"); // STABLE → release SUCCESS
                        markTraffic(releaseId, 100);
                        activityLog.log(appIdOf(releaseId), releaseId, "SYSTEM", null,
                                "EXECUTE_SUCCESS", "流水线执行完成，部署成功", envOf(releaseId));
                        activePipelines.remove(releaseId);
                        return;
                    }
                    case "FAILED" -> {
                        persistenceService.advanceStage(releaseId, deployStage > 0 ? deployStage : 2, "FAILED");
                        activityLog.log(appIdOf(releaseId), releaseId, "SYSTEM", null,
                                "EXECUTE_FAILED", "流水线失败: " + status.get("error"), envOf(releaseId));
                        activePipelines.remove(releaseId);
                        return;
                    }
                    default -> { // PENDING/CLONING/MERGING/BUILDING 仍在构建阶段
                    }
                }
                if (finished && !"SUCCESS".equals(st) && !"FAILED".equals(st)) {
                    // 触发器报告结束但状态异常，按失败处理
                    persistenceService.advanceStage(releaseId, deployStage > 0 ? deployStage : 2, "FAILED");
                    activityLog.log(appIdOf(releaseId), releaseId, "SYSTEM", null,
                            "EXECUTE_FAILED", "流水线异常终止: " + st, envOf(releaseId));
                    activePipelines.remove(releaseId);
                    return;
                }
                Thread.sleep(3000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("watchPipeline release {} failed", releaseId, e);
            persistenceService.advanceStage(releaseId, 2, "FAILED");
            activityLog.log(appIdOf(releaseId), releaseId, "SYSTEM", null,
                    "EXECUTE_FAILED", "监控流水线异常: " + e.getMessage(), envOf(releaseId));
            activePipelines.remove(releaseId);
        }
    }

    private void markTraffic(Long releaseId, int traffic) {
        releaseRepository.findById(releaseId).ifPresent(r -> {
            r.setCurrentTraffic(traffic);
            releaseRepository.save(r);
        });
    }

    private Long appIdOf(Long releaseId) {
        return releaseRepository.findById(releaseId).map(Release::getAppId).orElse(null);
    }

    private String envOf(Long releaseId) {
        return releaseRepository.findById(releaseId).map(Release::getEnv).orElse(null);
    }

    private String gitUrlFor(Release release) {
        return applicationRepository.findById(release.getAppId())
                .map(Application::getRepoUrl)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "应用不存在，无法确定代码仓库: " + release.getAppId()));
    }

    private String branchFor(Artifact artifact) {
        return artifact.getGitBranch() != null && !artifact.getGitBranch().isBlank()
                ? artifact.getGitBranch() : "main";
    }

    /** 当前关联的流水线 ID（页面跳日志用）。 */
    public String pipelineIdOf(Long releaseId) {
        return activePipelines.get(releaseId);
    }
}
