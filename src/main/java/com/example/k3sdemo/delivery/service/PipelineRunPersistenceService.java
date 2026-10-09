package com.example.k3sdemo.delivery.service;

import com.example.k3sdemo.delivery.entity.PipelineRunEntity;
import com.example.k3sdemo.delivery.repository.PipelineRunRepository;
import com.example.k3sdemo.model.PipelineConfig;
import com.example.k3sdemo.model.PipelineRun;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 流水线运行持久化服务（方案 B）：把 DevOpsService 内存中的 PipelineRun 同步到 pipeline_run 表。
 * 设计原则：DB 故障不影响流水线执行，所有异常只记日志。
 */
@Service
public class PipelineRunPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunPersistenceService.class);

    private final PipelineRunRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public PipelineRunPersistenceService(PipelineRunRepository repository) {
        this.repository = repository;
    }

    /**
     * 流水线状态/日志变化时调用（独立事务，失败静默）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onChanged(PipelineRun run) {
        save(run);
    }

    /**
     * 流水线结束时调用（独立事务，失败静默）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompleted(PipelineRun run) {
        save(run);
    }

    /**
     * 启动时加载所有历史记录（含已完成），回填内存。
     */
    @Transactional(readOnly = true)
    public List<PipelineRun> loadAll() {
        try {
            List<PipelineRunEntity> entities = repository.findAll();
            List<PipelineRun> runs = new ArrayList<>();
            for (PipelineRunEntity entity : entities) {
                runs.add(toModel(entity));
            }
            return runs;
        } catch (DataAccessException e) {
            log.warn("[pipeline-persist] 加载流水线历史失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 把状态仍为非终态（PENDING/CLONING/.../DEPLOYING）的记录标记为 FAILED。
     * 场景：应用重启前正在执行的流水线，其执行线程已消失，属于僵尸任务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markZombiesFailed() {
        try {
            List<PipelineRunEntity> zombies = repository.findByStatusIn(List.of(
                    PipelineRun.Status.PENDING.name(),
                    PipelineRun.Status.CLONING.name(),
                    PipelineRun.Status.MERGING.name(),
                    PipelineRun.Status.BUILDING.name(),
                    PipelineRun.Status.PUSHING.name(),
                    PipelineRun.Status.DEPLOYING.name()));
            for (PipelineRunEntity entity : zombies) {
                entity.setStatus(PipelineRun.Status.FAILED.name());
                entity.setCurrentStep(5);
                entity.setErrorMessage("应用重启前中断（执行线程已终止）");
                List<String> logs = deserializeLogs(entity.getLogs());
                logs.add("[ERROR] 应用重启前中断（执行线程已终止）");
                try {
                    entity.setLogs(serializeLogs(logs));
                } catch (Exception e) {
                    // 序列化失败则保留原日志，不阻塞状态修复
                }
                entity.setFinishedAt(java.time.LocalDateTime.now());
                repository.save(entity);
            }
            if (!zombies.isEmpty()) {
                log.info("[pipeline-persist] 已将 {} 条僵尸流水线标记为 FAILED", zombies.size());
            }
        } catch (DataAccessException e) {
            log.warn("[pipeline-persist] 标记僵尸任务失败: {}", e.getMessage());
        }
    }

    /**
     * 按 runId 从 DB 查询（供 BuildKitPipelineTrigger 跨重启轮询）。
     */
    @Transactional(readOnly = true)
    public Optional<PipelineRun> load(String runId) {
        try {
            return repository.findByRunId(runId).map(this::toModel);
        } catch (DataAccessException e) {
            log.warn("[pipeline-persist] 从 DB 加载流水线 {} 失败: {}", runId, e.getMessage());
            return Optional.empty();
        }
    }

    // --- 内部实现 ---

    private void save(PipelineRun run) {
        try {
            PipelineRunEntity entity = repository.findByRunId(run.getId())
                    .orElseGet(PipelineRunEntity::new);
            entity.setRunId(run.getId());
            entity.setStatus(run.getStatus().name());
            entity.setCurrentStep(run.getCurrentStep());
            entity.setErrorMessage(run.getErrorMessage());
            PipelineConfig config = run.getConfig();
            entity.setGitUrl(config.getGitUrl());
            entity.setBranch(config.getBranch());
            entity.setImageName(config.getImageName());
            entity.setImageTag(config.getImageTag());
            entity.setConfigJson(serializeConfig(config));
            entity.setLogs(serializeLogs(run.getLogs()));
            entity.setMergeCommitSha(run.getMergeCommitSha());
            entity.setConflictFiles(serializeLogs(new ArrayList<>(run.getConflictFiles())));
            entity.setPreviewNamespace(run.getPreviewNamespace());
            entity.setPreviewNodePortUrl(run.getPreviewNodePortUrl());
            entity.setStartedAt(run.getStartTime());
            entity.setFinishedAt(run.getEndTime());
            repository.save(entity);
        } catch (DataAccessException e) {
            log.warn("[pipeline-persist] 保存流水线 {} 到 DB 失败: {}", run.getId(), e.getMessage());
        } catch (Exception e) {
            log.warn("[pipeline-persist] 序列化流水线 {} 失败: {}", run.getId(), e.getMessage());
        }
    }

    private PipelineRun toModel(PipelineRunEntity entity) {
        PipelineConfig config = deserializeConfig(entity.getConfigJson());
        PipelineRun run = new PipelineRun(config);
        run.restoreStatus(
                entity.getRunId(),
                PipelineRun.Status.valueOf(entity.getStatus()),
                entity.getCurrentStep(),
                entity.getErrorMessage(),
                deserializeLogs(entity.getLogs()),
                entity.getMergeCommitSha(),
                deserializeLogs(entity.getConflictFiles()),
                entity.getPreviewNamespace(),
                entity.getPreviewNodePortUrl(),
                entity.getStartedAt(),
                entity.getFinishedAt());
        return run;
    }

    private String serializeConfig(PipelineConfig config) throws Exception {
        return objectMapper.writeValueAsString(config);
    }

    private PipelineConfig deserializeConfig(String json) {
        try {
            return objectMapper.readValue(json, PipelineConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化 PipelineConfig 失败", e);
        }
    }

    private String serializeLogs(List<String> logs) throws Exception {
        return objectMapper.writeValueAsString(logs != null ? logs : List.of());
    }

    private List<String> deserializeLogs(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[pipeline-persist] 反序列化日志失败，返回空列表: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
