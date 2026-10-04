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
 * 应用环境启用配置（表 app_environment，设计 3.2）。
 * 一个应用对 PREVIEW/BETA/PROD 三个环境的启用开关。
 */
@Entity
@Table(name = "app_environment")
public class AppEnvironment {

    public static final String PREVIEW = "PREVIEW";
    public static final String BETA = "BETA";
    public static final String PROD = "PROD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    /** PREVIEW / BETA / PROD */
    @Column(nullable = false, length = 16)
    private String env;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** 环境级配置 JSON，如预览环境回收时长 */
    @Column(name = "config_json", columnDefinition = "json")
    private String configJson;

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
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
