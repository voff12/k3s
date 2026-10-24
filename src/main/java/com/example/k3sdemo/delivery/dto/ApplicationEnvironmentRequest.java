package com.example.k3sdemo.delivery.dto;

/** 保存应用在某环境的部署模板覆盖。 */
public class ApplicationEnvironmentRequest {
    private String env;
    private DeploymentTemplate deploymentOverride;

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public DeploymentTemplate getDeploymentOverride() { return deploymentOverride; }
    public void setDeploymentOverride(DeploymentTemplate deploymentOverride) { this.deploymentOverride = deploymentOverride; }
}
