package com.example.k3sdemo.delivery.dto;

/**
 * 新建发布向导提交（设计 4.8 POST /api/delivery/releases）。
 */
public class CreateReleaseRequest {

    private Long applicationId;
    private Long artifactId;
    /** PREVIEW / BETA / PROD */
    private String targetEnv;
    /** CANARY / BLUE_GREEN / DEPLOY_ONLY */
    private String strategy;
    private String note;
    private String operatorName;

    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long applicationId) { this.applicationId = applicationId; }
    public Long getArtifactId() { return artifactId; }
    public void setArtifactId(Long artifactId) { this.artifactId = artifactId; }
    public String getTargetEnv() { return targetEnv; }
    public void setTargetEnv(String targetEnv) { this.targetEnv = targetEnv; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
}
