package com.example.k3sdemo.delivery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * CI/CD 流水线运行记录（表 pipeline_run，V3 迁移）。
 * 对应内存模型 {@link com.example.k3sdemo.model.PipelineRun} 的持久化快照：
 * 状态/步骤/错误 + config_json + logs 全量保存，应用重启后从本表恢复列表与详情。
 */
@Entity
@Table(name = "pipeline_run")
public class PipelineRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PipelineRun 内存 ID（8 位），与 SSE/页面 URL 一致 */
    @Column(name = "run_id", nullable = false, length = 32, unique = true)
    private String runId;

    /** PENDING/CLONING/MERGING/BUILDING/PUSHING/DEPLOYING/SUCCESS/FAILED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "current_step", nullable = false)
    private int currentStep = -1;

    @Column(name = "error_message", length = 65535)
    private String errorMessage;

    @Column(name = "git_url", nullable = false, length = 512)
    private String gitUrl;

    @Column(length = 128)
    private String branch;

    @Column(name = "image_name", nullable = false, length = 256)
    private String imageName;

    @Column(name = "image_tag", nullable = false, length = 64)
    private String imageTag = "latest";

    /** PipelineConfig 全量序列化（JSON），用于重启后恢复完整上下文 */
    @Column(name = "config_json", nullable = false, length = 65535)
    private String configJson;

    /** 全量带时间戳日志行（JSON 数组），强制 LONGVARCHAR 避免方言差异 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16777215)
    private String logs;

    @Column(name = "merge_commit_sha", length = 40)
    private String mergeCommitSha;

    @Column(name = "conflict_files", length = 65535)
    private String conflictFiles;

    @Column(name = "preview_namespace", length = 64)
    private String previewNamespace;

    @Column(name = "preview_nodeport_url", length = 256)
    private String previewNodePortUrl;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getCurrentStep() { return currentStep; }
    public void setCurrentStep(int currentStep) { this.currentStep = currentStep; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getGitUrl() { return gitUrl; }
    public void setGitUrl(String gitUrl) { this.gitUrl = gitUrl; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getImageName() { return imageName; }
    public void setImageName(String imageName) { this.imageName = imageName; }
    public String getImageTag() { return imageTag; }
    public void setImageTag(String imageTag) { this.imageTag = imageTag; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getLogs() { return logs; }
    public void setLogs(String logs) { this.logs = logs; }
    public String getMergeCommitSha() { return mergeCommitSha; }
    public void setMergeCommitSha(String mergeCommitSha) { this.mergeCommitSha = mergeCommitSha; }
    public String getConflictFiles() { return conflictFiles; }
    public void setConflictFiles(String conflictFiles) { this.conflictFiles = conflictFiles; }
    public String getPreviewNamespace() { return previewNamespace; }
    public void setPreviewNamespace(String previewNamespace) { this.previewNamespace = previewNamespace; }
    public String getPreviewNodePortUrl() { return previewNodePortUrl; }
    public void setPreviewNodePortUrl(String previewNodePortUrl) { this.previewNodePortUrl = previewNodePortUrl; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
