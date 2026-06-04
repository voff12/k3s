package com.example.k3sdemo.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    private String gitProxy; // HTTP 代理, 例如 http://127.0.0.1:7890

    // ===== 多分支合并预览部署 (Merge Preview Deploy) =====
    /** 合并基底分支 (base)。为空时回退到 branch。 */
    private String baseBranch;
    /** 待合并的 feature 分支列表。非空即为"合并部署"模式。 */
    private List<String> featureBranches = new ArrayList<>();
    /** 预览环境自动回收时长(分钟)。 */
    private int previewTtlMinutes = 120;

    // ===== 多语言运行时 (Runtime) =====
    /** java / python / dockerfile / auto(默认)。 */
    private String runtime = "auto";
    /** Python 基础镜像版本 → python:<ver>-slim。 */
    private String pythonVersion = "3.11";
    /** 应用监听端口;0 表示按 runtime 取默认(java=8080, python=8000)。 */
    private int appPort = 0;
    /** Python Web 服务器:gunicorn(WSGI)/ uvicorn(ASGI)。 */
    private String pythonServer = "gunicorn";
    /** WSGI/ASGI 入口模块,如 app:app。 */
    private String appModule = "app:app";
    /** 依赖清单路径。 */
    private String requirementsPath = "requirements.txt";
    /** 是否安装 C 扩展编译链(build-essential gcc)。 */
    private boolean buildDeps = false;
    /** 可选,自定义启动命令(覆盖自动拼装,用于非 Web/worker 场景)。 */
    private String startCommand;

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

    public String getGitProxy() {
        return gitProxy;
    }

    public void setGitProxy(String gitProxy) {
        this.gitProxy = gitProxy;
    }

    /**
     * Check if this pipeline uses a Git HTTP proxy.
     */
    public boolean hasGitProxy() {
        return gitProxy != null && !gitProxy.isEmpty();
    }

    /**
     * Returns the full image reference.
     * 离线模式: 直接使用 imageName:tag (本地 ctr import, 不需要 Harbor 前缀)
     */
    public String getFullImageRef(String harborHost, String harborProject) {
        return imageName + ":" + getEffectiveImageTag();
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

    /**
     * 实际合并基底分支: baseBranch 优先, 回退到 branch (向后兼容)。
     */
    public String getEffectiveBaseBranch() {
        if (baseBranch != null && !baseBranch.isEmpty()) {
            return baseBranch;
        }
        return branch != null && !branch.isEmpty() ? branch : "main";
    }

    /**
     * 去重 + 去空白后的 feature 分支列表 (排除与 base 相同的分支)。
     */
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

    /**
     * 是否为多分支合并部署模式 (有至少一个 feature 分支)。
     */
    public boolean isMergeDeploy() {
        return !getNormalizedFeatureBranches().isEmpty();
    }

    /**
     * 合并集 ID: 对 base + 排序后的 feature 列表做哈希, 与分支顺序无关。
     * 相同分支组合 → 相同 mergeSetId → 幂等复用同一预览环境。
     */
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

    /**
     * 预览命名空间名: preview-<mergeSetId>。
     */
    public String getPreviewNamespace() {
        return "preview-" + getMergeSetId();
    }

    /**
     * 合并模式下的镜像 tag: preview-<mergeSetId>, 保证不同分支组合互不覆盖。
     */
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

    /** 解析后的 runtime:显式值(java/python/dockerfile),否则 auto。 */
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

    /** 生效端口:appPort>0 用之;否则 python=8000,其它=8080。 */
    public int getEffectiveAppPort() {
        if (appPort > 0) {
            return appPort;
        }
        return isPython() ? 8000 : 8080;
    }

    /**
     * 校验运行时相关字段(防 shell 注入);返回错误信息,合法返回 null。
     */
    public String validateRuntimeFields() {
        return RuntimeValidation.validate(runtime, pythonVersion, appPort, pythonServer,
                appModule, requirementsPath);
    }
}
