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
 * 接入的应用（表 application）。
 * 软删除：status = DISABLED。
 */
@Entity
@Table(name = "application")
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    /** 英文标识，如 order-service，全局唯一 */
    @Column(nullable = false, length = 64, unique = true)
    private String code;

    @Column(length = 64)
    private String team;

    @Column(name = "repo_url", nullable = false, length = 512)
    private String repoUrl;

    /** 私有仓库访问 token（各应用自配）；查询接口不回显明文 */
    @Column(name = "git_token", length = 256)
    private String gitToken;

    /** gitlab / github / other */
    @Column(name = "repo_provider", nullable = false, length = 16)
    private String repoProvider = "gitlab";

    @Column(name = "default_branch", nullable = false, length = 64)
    private String defaultBranch = "main";

    /** java / python / docker 等，接入时自动识别 */
    @Column(name = "runtime_type", length = 16)
    private String runtimeType;

    @Column
    private Integer port;

    @Column(length = 64)
    private String namespace;

    @Column(name = "health_path", length = 256)
    private String healthPath;

    @Column(name = "policy_template_id")
    private Long policyTemplateId;

    @Column(name = "prod_enabled", nullable = false)
    private Boolean prodEnabled = false;

    /** ACTIVE / DISABLED */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getGitToken() { return gitToken; }
    public void setGitToken(String gitToken) { this.gitToken = gitToken; }
    public String getRepoProvider() { return repoProvider; }
    public void setRepoProvider(String repoProvider) { this.repoProvider = repoProvider; }
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public String getRuntimeType() { return runtimeType; }
    public void setRuntimeType(String runtimeType) { this.runtimeType = runtimeType; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getHealthPath() { return healthPath; }
    public void setHealthPath(String healthPath) { this.healthPath = healthPath; }
    public Long getPolicyTemplateId() { return policyTemplateId; }
    public void setPolicyTemplateId(Long policyTemplateId) { this.policyTemplateId = policyTemplateId; }
    public Boolean getProdEnabled() { return prodEnabled; }
    public void setProdEnabled(Boolean prodEnabled) { this.prodEnabled = prodEnabled; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
