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
 * 构建制品（表 artifact，设计 3.3）。digest 全局唯一，跨环境复用。
 */
@Entity
@Table(name = "artifact")
public class Artifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    /** 版本号，如 v2.8.0 */
    @Column(nullable = false, length = 64)
    private String version;

    /** 构建对应 commit（完整 40 位） */
    @Column(name = "git_sha", nullable = false, length = 40)
    private String gitSha;

    @Column(name = "git_branch", length = 64)
    private String gitBranch;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "pr_title", length = 256)
    private String prTitle;

    /** Harbor 仓库地址 */
    @Column(name = "image_repo", nullable = false, length = 256)
    private String imageRepo;

    /** sha256:...，跨环境复用的唯一凭证 */
    @Column(name = "image_digest", nullable = false, length = 96, unique = true)
    private String imageDigest;

    /** PENDING / PASSED / FAILED */
    @Column(name = "scan_status", nullable = false, length = 16)
    private String scanStatus = "PENDING";

    /** 漏洞摘要 JSON，如 {"critical":1,"high":0} */
    @Column(name = "scan_summary", columnDefinition = "TEXT")
    private String scanSummary;

    /** PENDING / GENERATED */
    @Column(name = "sbom_status", nullable = false, length = 16)
    private String sbomStatus = "PENDING";

    /** 构建状态 */
    @Column(name = "build_status", nullable = false, length = 16)
    private String buildStatus = "SUCCESS";

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
    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getGitSha() { return gitSha; }
    public void setGitSha(String gitSha) { this.gitSha = gitSha; }
    public String getGitBranch() { return gitBranch; }
    public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }
    public Integer getPrNumber() { return prNumber; }
    public void setPrNumber(Integer prNumber) { this.prNumber = prNumber; }
    public String getPrTitle() { return prTitle; }
    public void setPrTitle(String prTitle) { this.prTitle = prTitle; }
    public String getImageRepo() { return imageRepo; }
    public void setImageRepo(String imageRepo) { this.imageRepo = imageRepo; }
    public String getImageDigest() { return imageDigest; }
    public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }
    public String getScanStatus() { return scanStatus; }
    public void setScanStatus(String scanStatus) { this.scanStatus = scanStatus; }
    public String getScanSummary() { return scanSummary; }
    public void setScanSummary(String scanSummary) { this.scanSummary = scanSummary; }
    public String getSbomStatus() { return sbomStatus; }
    public void setSbomStatus(String sbomStatus) { this.sbomStatus = sbomStatus; }
    public String getBuildStatus() { return buildStatus; }
    public void setBuildStatus(String buildStatus) { this.buildStatus = buildStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
