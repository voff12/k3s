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
 * 发布人工审批门（表 approval，V1 已建表）：requireProdApproval=true 时
 * 在 CANARY 前生成 PENDING 记录，审批通过后发布继续推进。
 */
@Entity
@Table(name = "approval")
public class ReleaseApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    /** 审批门：PROD_START（进入生产灰度前） */
    @Column(nullable = false, length = 32)
    private String gate;

    /** PENDING / APPROVED / REJECTED */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(length = 64)
    private String requester;

    @Column(length = 64)
    private String approver;

    @Column(length = 512)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public Long getReleaseId() { return releaseId; }
    public void setReleaseId(Long releaseId) { this.releaseId = releaseId; }
    public String getGate() { return gate; }
    public void setGate(String gate) { this.gate = gate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequester() { return requester; }
    public void setRequester(String requester) { this.requester = requester; }
    public String getApprover() { return approver; }
    public void setApprover(String approver) { this.approver = approver; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
}
