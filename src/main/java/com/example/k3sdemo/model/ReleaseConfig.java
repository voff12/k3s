package com.example.k3sdemo.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    private String gitProxy;  // HTTP 代理，如 http://10.0.0.1:7890，用于访问 GitHub
    private String buildCommand = "mvn clean package -DskipTests";
    private String harborProject = "library";

    // ===== 多分支合并预览部署 =====
    private String baseBranch;
    private List<String> featureBranches = new ArrayList<>();
    private int previewTtlMinutes = 120;

    // ===== 多语言运行时 =====
    private String runtime = "auto";
    private String pythonVersion = "3.11";
    private int appPort = 0;
    private String pythonServer = "gunicorn";
    private String appModule = "app:app";
    private String requirementsPath = "requirements.txt";
    private boolean buildDeps = false;
    private String startCommand;

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

    public String getGitProxy() {
        return gitProxy;
    }

    public void setGitProxy(String gitProxy) {
        this.gitProxy = gitProxy;
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

    public boolean hasGitProxy() {
        return gitProxy != null && !gitProxy.isEmpty();
    }

    public boolean hasBuildStep() {
        return buildCommand != null && !buildCommand.isEmpty();
    }

    /**
     * 获取完整 Harbor 镜像引用: harbor.local/project/imageName:tag
     */
    public String getFullHarborImageRef(String harborHost) {
        return harborHost + "/" + harborProject + "/" + imageName + ":" + getEffectiveImageTag();
    }

    // ===== 多分支合并预览部署 =====

    public String getBaseBranch() {
        return baseBranch;
    }

    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public List<String> getFeatureBranches() {
        return featureBranches;
    }

    public void setFeatureBranches(List<String> featureBranches) {
        this.featureBranches = featureBranches != null ? featureBranches : new ArrayList<>();
    }

    public int getPreviewTtlMinutes() {
        return previewTtlMinutes;
    }

    public void setPreviewTtlMinutes(int previewTtlMinutes) {
        this.previewTtlMinutes = previewTtlMinutes;
    }

    public String getEffectiveBaseBranch() {
        if (baseBranch != null && !baseBranch.isEmpty()) {
            return baseBranch;
        }
        return branch != null && !branch.isEmpty() ? branch : "main";
    }

    public List<String> getNormalizedFeatureBranches() {
        if (featureBranches == null) {
            return new ArrayList<>();
        }
        String base = getEffectiveBaseBranch();
        return featureBranches.stream()
                .filter(b -> b != null && !b.trim().isEmpty())
                .map(String::trim)
                .filter(b -> !b.equals(base))
                .distinct()
                .collect(Collectors.toList());
    }

    public boolean isMergeDeploy() {
        return !getNormalizedFeatureBranches().isEmpty();
    }

    public String getMergeSetId() {
        List<String> feats = getNormalizedFeatureBranches();
        feats.sort(String::compareTo);
        String seed = getEffectiveBaseBranch() + "|" + String.join(",", feats);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(seed.hashCode());
        }
    }

    public String getPreviewNamespace() {
        return "preview-" + getMergeSetId();
    }

    public String getEffectiveImageTag() {
        if (isMergeDeploy()) {
            return "preview-" + getMergeSetId();
        }
        return imageTag != null && !imageTag.isEmpty() ? imageTag : "latest";
    }

    // ===== 多语言运行时 =====

    public String getRuntime() {
        return runtime;
    }

    public void setRuntime(String runtime) {
        this.runtime = runtime;
    }

    public String getPythonVersion() {
        return pythonVersion;
    }

    public void setPythonVersion(String pythonVersion) {
        this.pythonVersion = pythonVersion;
    }

    public int getAppPort() {
        return appPort;
    }

    public void setAppPort(int appPort) {
        this.appPort = appPort;
    }

    public String getPythonServer() {
        return pythonServer;
    }

    public void setPythonServer(String pythonServer) {
        this.pythonServer = pythonServer;
    }

    public String getAppModule() {
        return appModule;
    }

    public void setAppModule(String appModule) {
        this.appModule = appModule;
    }

    public String getRequirementsPath() {
        return requirementsPath;
    }

    public void setRequirementsPath(String requirementsPath) {
        this.requirementsPath = requirementsPath;
    }

    public boolean isBuildDeps() {
        return buildDeps;
    }

    public void setBuildDeps(boolean buildDeps) {
        this.buildDeps = buildDeps;
    }

    public String getStartCommand() {
        return startCommand;
    }

    public void setStartCommand(String startCommand) {
        this.startCommand = startCommand;
    }

    public String resolveRuntime() {
        if (runtime == null || runtime.isEmpty()) {
            return "auto";
        }
        String r = runtime.trim().toLowerCase();
        return switch (r) {
            case "java", "python", "dockerfile" -> r;
            default -> "auto";
        };
    }

    public boolean isPython() {
        return "python".equals(resolveRuntime());
    }

    public int getEffectiveAppPort() {
        if (appPort > 0) {
            return appPort;
        }
        return isPython() ? 8000 : 8080;
    }

    public String validateRuntimeFields() {
        return RuntimeValidation.validate(runtime, pythonVersion, appPort, pythonServer,
                appModule, requirementsPath);
    }
}
