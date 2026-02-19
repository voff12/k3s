package com.example.k3sdemo.model;

/**
 * 发布配置 DTO — 用于创建新的应用发布。
 */
public class ReleaseConfig {

    private String gitUrl;
    private String branch = "main";
    private String imageName;
    private String imageTag = "latest";
    private String namespace = "default";
    private String deploymentName;
    private String dockerfilePath = "./Dockerfile";
    private String gitToken;
    private String buildCommand = "mvn clean package -DskipTests";
    private String harborProject = "library";

    public ReleaseConfig() {
    }

    // --- Getters & Setters ---

    public String getGitUrl() {
        return gitUrl;
    }

    public void setGitUrl(String gitUrl) {
        this.gitUrl = gitUrl;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getImageTag() {
        return imageTag;
    }

    public void setImageTag(String imageTag) {
        this.imageTag = imageTag;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public void setDeploymentName(String deploymentName) {
        this.deploymentName = deploymentName;
    }

    public String getDockerfilePath() {
        return dockerfilePath;
    }

    public void setDockerfilePath(String dockerfilePath) {
        this.dockerfilePath = dockerfilePath;
    }

    public String getGitToken() {
        return gitToken;
    }

    public void setGitToken(String gitToken) {
        this.gitToken = gitToken;
    }

    public String getBuildCommand() {
        return buildCommand;
    }

    public void setBuildCommand(String buildCommand) {
        this.buildCommand = buildCommand;
    }

    public String getHarborProject() {
        return harborProject;
    }

    public void setHarborProject(String harborProject) {
        this.harborProject = harborProject;
    }

    // --- Helper methods ---

    public boolean hasGitAuth() {
        return gitToken != null && !gitToken.isEmpty();
    }

    public boolean hasBuildStep() {
        return buildCommand != null && !buildCommand.isEmpty();
    }

    /**
     * 获取完整 Harbor 镜像引用: harbor.local/project/imageName:tag
     */
    public String getFullHarborImageRef(String harborHost) {
        return harborHost + "/" + harborProject + "/" + imageName + ":" + imageTag;
    }
}
