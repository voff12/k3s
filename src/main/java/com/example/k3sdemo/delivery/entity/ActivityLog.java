package com.example.k3sdemo.delivery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 统一活动流水（表 activity_log，设计 3.10）。
 * 只增不改：人工操作、规则动作、AI 产出、系统事件都写这里。
 */
@Entity
@Table(name = "activity_log")
public class ActivityLog {

    public enum ActorType { USER, RULE, AI, SYSTEM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id")
    private Long appId;

    @Column(name = "release_id")
    private Long releaseId;

    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    @Column(length = 64)
    private String actor;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(nullable = false, length = 512)
    private String message;

    /** CI / PREVIEW / BETA / PROD */
    @Column(length = 16)
    private String env;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }
    public Long getReleaseId() { return releaseId; }
    public void setReleaseId(Long releaseId) { this.releaseId = releaseId; }
    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
