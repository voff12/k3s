package com.example.k3sdemo.model;

/**
 * Pipeline input configuration DTO.
 */
public class PipelineConfig {

    private String gitUrl;
    private String branch = "main";
    private String imageName;
    private String imageTag = "latest";
    private String namespace = "default";
    private String deploymentName;
    private String dockerfilePath = "./Dockerfile";
    private String gitToken;
    private String buildCommand = "mvn clean package -DskipTests";

    public PipelineConfig() {
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

    /**
     * Check if this pipeline uses Git authentication (for private GitLab/GitHub
     * repos).
     */
    public boolean hasGitAuth() {
        return gitToken != null && !gitToken.isEmpty();
    }

    public String getBuildCommand() {
        return buildCommand;
    }

    public void setBuildCommand(String buildCommand) {
        this.buildCommand = buildCommand;
    }

    /**
     * Check if this pipeline needs a build step (Maven/Gradle).
     */
    public boolean hasBuildStep() {
        return buildCommand != null && !buildCommand.isEmpty();
    }

    /**
     * Returns the full image reference including Harbor host, e.g.
     * harbor.local/library/myapp:latest
     */
    public String getFullImageRef(String harborHost, String harborProject) {
        return harborHost + "/" + harborProject + "/" + imageName + ":" + imageTag;
    }
}
