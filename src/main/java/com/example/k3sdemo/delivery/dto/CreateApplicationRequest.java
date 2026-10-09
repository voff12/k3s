package com.example.k3sdemo.delivery.dto;

import java.util.List;

/**
 * 接入向导最终提交（设计 4.2 POST /applications）。
 */
public class CreateApplicationRequest {

    private String name;
    private String code;
    private String team;
    private String repoUrl;
    /** 私有仓库访问 token（各应用自配），登记制品自动关联最新提交时用 */
    private String gitToken;
    private String repoProvider;
    private String defaultBranch;
    private String runtimeType;
    private Integer port;
    private String namespace;
    private String healthPath;
    private String policyTemplateCode;
    private Boolean prodEnabled;
    /** 触发人（无用户体系，暂由前端传入） */
    private String operatorName;
    /** 启用的环境：PREVIEW / BETA / PROD */
    private List<String> environments;

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
    public String getPolicyTemplateCode() { return policyTemplateCode; }
    public void setPolicyTemplateCode(String policyTemplateCode) { this.policyTemplateCode = policyTemplateCode; }
    public Boolean getProdEnabled() { return prodEnabled; }
    public void setProdEnabled(Boolean prodEnabled) { this.prodEnabled = prodEnabled; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public List<String> getEnvironments() { return environments; }
    public void setEnvironments(List<String> environments) { this.environments = environments; }
}
