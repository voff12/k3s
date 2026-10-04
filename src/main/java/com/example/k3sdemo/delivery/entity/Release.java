package com.example.k3sdemo.delivery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 发布记录（表 release，设计 3.4）。
 */
@Entity
@Table(name = "release")
public class Release {

    public enum Status {
        PENDING, RUNNING, AWAITING_APPROVAL, PAUSED, SUCCESS, FAILED, ROLLED_BACK
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务编号，如 rel-20261005-018 */
    @Column(name = "release_no", nullable = false, length = 32, unique = true)
    private String releaseNo;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "artifact_id", nullable = false)
    private Long artifactId;

    /** PREVIEW / BETA / PROD */
    @Column(nullable = false, length = 16)
    private String env;

    /** CANARY / BLUE_GREEN / DEPLOY_ONLY */
    @Column(nullable = false, length = 16)
    private String strategy;

    @Column(nullable = false, length = 16)
    private String status = Status.PENDING.name();

    @Column(name = "current_stage", length = 32)
    private String currentStage;

    @Column(name = "current_traffic")
    private Integer currentTraffic = 0;

    @Column(length = 512)
    private String note;

    @Column(length = 64)
    private String operator;

    @Column(name = "started_at")
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
    public String getReleaseNo() { return releaseNo; }
    public void setReleaseNo(String releaseNo) { this.releaseNo = releaseNo; }
    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }
    public Long getArtifactId() { return artifactId; }
    public void setArtifactId(Long artifactId) { this.artifactId = artifactId; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrentStage() { return currentStage; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public Integer getCurrentTraffic() { return currentTraffic; }
    public void setCurrentTraffic(Integer currentTraffic) { this.currentTraffic = currentTraffic; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
