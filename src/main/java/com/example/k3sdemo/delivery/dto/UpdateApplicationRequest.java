package com.example.k3sdemo.delivery.dto;

/**
 * 应用编辑（PUT /applications/{id}）：全部字段可选，仅更新传入的字段。
 * gitToken 三态：null = 不变；"" = 清除；非空 = 替换。
 */
public class UpdateApplicationRequest {

    private String repoUrl;
    private String gitToken;
    private String repoProvider;
    private String defaultBranch;
    /** 触发人（无用户体系，暂由前端传入） */
    private String operatorName;

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getGitToken() { return gitToken; }
    public void setGitToken(String gitToken) { this.gitToken = gitToken; }
    public String getRepoProvider() { return repoProvider; }
    public void setRepoProvider(String repoProvider) { this.repoProvider = repoProvider; }
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
}
