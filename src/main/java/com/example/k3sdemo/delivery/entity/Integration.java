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
 * 集成连接（表 integration，设计 3.11）。
 * 只存连接元数据与凭据引用（secret_ref），不存明文凭据。
 */
@Entity
@Table(name = "integration")
public class Integration {

    public static final String TYPE_GIT = "GIT";
    public static final String TYPE_HARBOR = "HARBOR";
    public static final String TYPE_PROMETHEUS = "PROMETHEUS";
    public static final String TYPE_NOTIFY = "NOTIFY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** GIT / HARBOR / PROMETHEUS / NOTIFY */
    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 256)
    private String endpoint;

    /** CONNECTED / PENDING / DISCONNECTED */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    /** 凭据引用（如 Nacos 配置 key），不存明文 */
    @Column(name = "secret_ref", length = 256)
    private String secretRef;

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
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSecretRef() { return secretRef; }
    public void setSecretRef(String secretRef) { this.secretRef = secretRef; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
