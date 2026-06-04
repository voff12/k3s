package com.example.k3sdemo.service;

import com.example.k3sdemo.model.ReleaseConfig;
import com.example.k3sdemo.model.ReleaseRecord;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

/**
 * 应用发布服务: 通过 K8s Job 编排 release-runner 完成
 * clone repo → mvn build → build image → push Harbor → kubectl deploy.
 */
@Service
public class ReleaseService {

    @Value("${k8s.kubeconfig:}")
    private String kubeconfig;

    @Value("${k8s.master.url:}")
    private String masterUrl;

    @Value("${harbor.host:harbor.local}")
    private String harborHost;

    @Value("${harbor.project:library}")
    private String harborProject;

    @Value("${harbor.username:admin}")
    private String harborUsername;

    @Value("${harbor.password:Harbor12345}")
    private String harborPassword;

    @Value("${harbor.ip:}")
    private String harborIp;

    @Value("${gitlab.token:}")
    private String globalGitlabToken;

    @Value("${kaniko.image:registry.aliyuncs.com/kaniko-project/executor:latest}")
    private String kanikoImage;

    @Value("${git.image:alpine:3.19}")
    private String gitImage;

    @Value("${maven.image:maven:3.9-eclipse-temurin-17}")
    private String mavenImage;

    @Value("${git.proxy:}")
    private String globalGitProxy;

    @Value("${release.base-image:}")
    private String releaseBaseImage;

    @Value("${pip.index:https://pypi.tuna.tsinghua.edu.cn/simple}")
    private String pipIndexUrl;

    private final Map<String, ReleaseRecord> releases = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @PostConstruct
    public void init() {
        if (kubeconfig != null && !kubeconfig.isEmpty()) {
            System.setProperty("kubeconfig", kubeconfig);
        }
        if (masterUrl != null && !masterUrl.isEmpty()) {
            System.setProperty("kubernetes.master", masterUrl);
        }
    }

    // ==================== 触发发布 ====================

    public ReleaseRecord triggerRelease(ReleaseConfig config) {
        ReleaseRecord record = new ReleaseRecord(config);
        releases.put(record.getId(), record);
        emitters.put(record.getId(), new CopyOnWriteArrayList<>());

        // 默认 Token
        if (!config.hasGitAuth() && globalGitlabToken != null && !globalGitlabToken.isEmpty()) {
            config.setGitToken(globalGitlabToken);
        }

        String fullImage = config.getFullHarborImageRef(harborHost);
        record.addLog("[INFO] 发布任务已创建, ID: " + record.getId());
        record.addLog("[INFO] Git 仓库: " + config.getGitUrl());
        if (config.isMergeDeploy()) {
            record.addLog("[INFO] 模式: 多分支合并预览部署");
            record.addLog("[INFO] 基底分支(base): " + config.getEffectiveBaseBranch());
            record.addLog("[INFO] 待合并分支: " + String.join(", ", config.getNormalizedFeatureBranches()));
            record.addLog("[INFO] 预览命名空间: " + config.getPreviewNamespace());
        } else {
            record.addLog("[INFO] 分支: " + config.getBranch());
        }
        record.addLog("[INFO] 目标镜像: " + fullImage);
        record.addLog("[INFO] Harbor 项目: " + config.getHarborProject());
        if (config.hasGitAuth()) {
            record.addLog("[INFO] Git 认证: 使用 Private Token");
        }
        if (config.hasGitProxy() || (globalGitProxy != null && !globalGitProxy.isEmpty())) {
            record.addLog("[INFO] Git 代理: " + (config.hasGitProxy() ? config.getGitProxy() : globalGitProxy));
        }

        executor.submit(() -> executeRelease(record));
        return record;
    }

    // ==================== 执行发布 ====================

    private void executeRelease(ReleaseRecord record) {
        ReleaseConfig config = record.getConfig();
        String fullImage = config.getFullHarborImageRef(harborHost);
        String jobName = "release-" + record.getId();

        // 如果没有指定 Deployment 名称，自动使用镜像名称（符合 K8s 命名规范）
        if (config.getDeploymentName() == null || config.getDeploymentName().isEmpty()) {
            String autoDeployName = config.getImageName()
                    .toLowerCase()
                    .replaceAll("[^a-z0-9-]", "-")
                    .replaceAll("-+", "-")
                    .replaceAll("^-|-$", "");
            config.setDeploymentName(autoDeployName);
            record.addLog("[INFO] 未指定 Deployment 名称，自动使用镜像名称: " + autoDeployName);
            broadcastLog(record);
        }

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            // ========== Step 1: 构建发布 (Clone + 构建 + Kaniko → Harbor) ==========
            record.advanceTo(ReleaseRecord.Status.BUILDING);
            broadcastStatus(record);
            String rt = config.resolveRuntime();
            String buildChain = config.isPython() ? "克隆 → pip 镜像构建 → Harbor"
                    : ("java".equals(rt) ? "克隆 → Maven → Kaniko → Harbor" : "克隆 → 构建 → Kaniko → Harbor");
            record.addLog("[INFO] ➜ 步骤 1/2: 构建发布 (" + buildChain + ") | 运行时: " + rt);
            broadcastLog(record);

            Job releaseJob = buildReleaseJob(jobName, record.getId(), config, fullImage);
            try {
                client.batch().v1().jobs().inNamespace("default").resource(releaseJob).create();
            } catch (KubernetesClientException e) {
                int code = e.getCode();
                if (code == 409) {
                    record.addLog("[WARN] Job " + jobName + " 已存在, 正在清理重建...");
                    broadcastLog(record);
                    cleanupJob(client, jobName);
                    Thread.sleep(3000);
                    client.batch().v1().jobs().inNamespace("default").resource(releaseJob).create();
                } else {
                    String hint = code == -1
                            ? " — 连不上 K8s API Server(网络/防火墙到 6443、kubeconfig server 地址、或 TLS)。"
                            : "";
                    record.fail("K8s API 错误 (" + code + "): " + e.getMessage() + hint
                            + " | 根因: " + rootCauseMsg(e));
                    broadcastStatus(record);
                    return;
                }
            }
            record.addLog("[INFO] K8s Job 已创建: " + jobName);
            broadcastLog(record);

            String podName = waitForPodName(client, jobName, record);
            if (podName == null) {
                record.fail("Pod 创建超时");
                broadcastStatus(record);
                return;
            }
            record.addLog("[INFO] Pod 已创建: " + podName);
            broadcastLog(record);

            // 1a: build 容器 (Git Clone [+ Merge] + Maven + Dockerfile + Docker Config)
            boolean buildOk = waitForInitContainerAndStreamLogs(client, podName, "build", record);
            if (!buildOk) {
                if (config.isMergeDeploy()) {
                    parseMergeResults(record);
                    if (!record.getConflictFiles().isEmpty()) {
                        record.fail("分支合并冲突，冲突文件: " + String.join(", ", record.getConflictFiles())
                                + " — 请人工解决后重试");
                    } else {
                        record.fail("分支合并/构建失败，请查看日志");
                    }
                } else {
                    record.fail("构建失败，请查看日志");
                }
                broadcastStatus(record);
                cleanupJob(client, jobName);
                return;
            }
            if (config.isMergeDeploy()) {
                parseMergeResults(record);
                record.addLog("[INFO] ✓ 多分支合并 + 准备完成, 开始 Kaniko 构建..."
                        + (record.getMergeCommitSha() != null ? " (merge commit: " + record.getMergeCommitSha() + ")" : ""));
            } else {
                record.addLog("[INFO] ✓ 代码克隆 + 准备完成, 开始 Kaniko 构建...");
            }
            broadcastLog(record);

            // 1b: Kaniko 构建镜像并推送到 Harbor
            boolean imageOk = waitForInitContainerAndStreamLogs(client, podName, "kaniko-build", record);
            if (!imageOk) {
                diagnoseMainContainerFailure(client, jobName, record);
                record.fail("镜像构建失败，请查看日志");
                broadcastStatus(record);
                return;
            }
            record.addLog("[INFO] ✓ 镜像构建并推送 Harbor 完成: " + fullImage);
            broadcastLog(record);

            // ========== Step 2: K3s 部署 ==========
            record.advanceTo(ReleaseRecord.Status.DEPLOYING);
            broadcastStatus(record);
            record.addLog("[INFO] ➜ 步骤 2/2: 部署到 K3s 集群...");
            broadcastLog(record);

            // Wait for main container (deployer) and stream its logs
            boolean podRunning = waitForPodRunning(client, podName, record);
            if (podRunning) {
                streamContainerLogs(client, podName, "default", "deployer", record);
            }

            boolean jobSuccess = waitForJobCompletion(client, jobName, record);

            // 现在 Deployment 名称总是会被设置（自动或手动）
            if (jobSuccess) {
                if (config.isMergeDeploy()) {
                    // 合并模式: 部署到独立预览命名空间 preview-<mergeSetId>
                    deployToPreview(client, config, fullImage, record);
                } else {
                    // Also do server-side deployment update as a fallback
                    deployToK3s(client, config, fullImage, record);
                }
            } else {
                diagnoseMainContainerFailure(client, jobName, record);
                record.fail("部署容器执行失败");
                broadcastStatus(record);
                return;
            }

            // ========== Done ==========
            record.advanceTo(ReleaseRecord.Status.SUCCESS);
            record.addLog("[INFO] ✓ 应用发布完成! 总耗时: " + record.getDuration());
            broadcastStatus(record);
            broadcastLog(record);
            // 确保前端收到最终状态后再发送 complete
            Thread.sleep(200);

        } catch (Exception e) {
            record.fail("发布异常: " + e.getMessage());
            broadcastStatus(record);
            broadcastLog(record);
        } finally {
            completeEmitters(record.getId());
        }
    }

    // ==================== Dockerfile 生成辅助 ====================

    /** 标准 .dockerignore(Python 镜像精简)。 */
    private static final String PY_DOCKERIGNORE =
            ".git\n__pycache__/\n*.pyc\n*.pyo\n.venv/\nvenv/\n.pytest_cache/\n.env\n";

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 取异常链最深处的根因(类名 + 消息)。 */
    private static String rootCauseMsg(Throwable t) {
        Throwable c = t;
        int guard = 0;
        while (c.getCause() != null && c.getCause() != c && guard++ < 20) {
            c = c.getCause();
        }
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Java runtime-only Dockerfile.release(COPY 已编译 jar)。 */
    private String buildJavaJarDockerfile(String baseImage) {
        return "FROM " + baseImage + "\n"
                + "WORKDIR /app\n"
                + "COPY target/*.jar app.jar\n"
                + "EXPOSE 8080\n"
                + "ENV JAVA_OPTS=\"-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0\"\n"
                + "ENTRYPOINT [\"sh\", \"-c\", \"java $JAVA_OPTS -jar /app/app.jar\"]\n";
    }

    /** Python 单阶段 Dockerfile(pip install + gunicorn/uvicorn),见 PYTHON-RELEASE-DESIGN.md 第五节。 */
    private String buildPythonDockerfile(String fromPrefix, ReleaseConfig config) {
        int port = config.getEffectiveAppPort();
        String pyver = (config.getPythonVersion() != null && !config.getPythonVersion().isEmpty())
                ? config.getPythonVersion() : "3.11";
        String server = "uvicorn".equals(config.getPythonServer()) ? "uvicorn" : "gunicorn";
        String module = (config.getAppModule() != null && !config.getAppModule().isEmpty())
                ? config.getAppModule() : "app:app";
        String rp = (config.getRequirementsPath() != null && !config.getRequirementsPath().isEmpty())
                ? config.getRequirementsPath() : "requirements.txt";
        StringBuilder df = new StringBuilder();
        df.append("FROM ").append(fromPrefix).append("/library/python:").append(pyver).append("-slim\n");
        df.append("WORKDIR /app\n");
        if (config.isBuildDeps()) {
            df.append("RUN apt-get update && apt-get install -y --no-install-recommends build-essential gcc"
                    + " && rm -rf /var/lib/apt/lists/*\n");
        }
        df.append("COPY ").append(rp).append(" ./requirements.txt\n");
        df.append("RUN pip install --no-cache-dir -i ").append(pipIndexUrl).append(" -r requirements.txt\n");
        df.append("RUN pip install --no-cache-dir -i ").append(pipIndexUrl).append(" ").append(server).append("\n");
        df.append("COPY . .\n");
        df.append("EXPOSE ").append(port).append("\n");
        String sc = config.getStartCommand();
        if (sc != null && !sc.trim().isEmpty()) {
            df.append("CMD [\"sh\", \"-c\", \"").append(jsonEscape(sc.trim())).append("\"]\n");
        } else if ("uvicorn".equals(server)) {
            df.append("CMD [\"uvicorn\", \"").append(module)
                    .append("\", \"--host\", \"0.0.0.0\", \"--port\", \"").append(port).append("\"]\n");
        } else {
            df.append("CMD [\"gunicorn\", \"-b\", \"0.0.0.0:").append(port).append("\", \"")
                    .append(module).append("\"]\n");
        }
        return df.toString();
    }

    // ==================== 构建 K8s Job ====================

    private Job buildReleaseJob(String jobName, String releaseId, ReleaseConfig config, String fullImage) {
        // Build git clone URL (with auth if needed)
        String cloneUrl;
        if (config.hasGitAuth()) {
            String gitUrl = config.getGitUrl();
            if (gitUrl.startsWith("https://")) {
                cloneUrl = "https://oauth2:" + config.getGitToken() + "@" + gitUrl.substring("https://".length());
            } else if (gitUrl.startsWith("http://")) {
                cloneUrl = "http://oauth2:" + config.getGitToken() + "@" + gitUrl.substring("http://".length());
            } else {
                cloneUrl = "https://oauth2:" + config.getGitToken() + "@" + gitUrl;
            }
        } else {
            cloneUrl = config.getGitUrl();
        }

        // Maven 构建命令
        String buildCmd = config.hasBuildStep() ? config.getBuildCommand() : "mvn clean package -DskipTests";

        // Dockerfile 不再使用用户原始路径, 而是由 prepare-kaniko 容器自动生成 Dockerfile.release

        // Kaniko 参数现在直接通过 withArgs 传递, 不再需要构建命令字符串

        // Deployer 命令: 使用 kubectl 更新或创建 Deployment
        // 注意：动态获取容器名，兼容 hello / hello-container 等不同命名
        String deployName = config.getDeploymentName();
        String ns = config.getNamespace();
        String deployCmd;
        if (config.isMergeDeploy()) {
            // 合并模式: 预览环境由服务端部署到 preview-<id> 命名空间, 此处不在 default 部署
            deployCmd = "echo '[INFO] 合并预览模式: 镜像已推送 Harbor, 预览环境将由服务端部署到独立命名空间'";
        } else {
            deployCmd = String.format(
                "echo '[INFO] 开始部署到 K3s (Deployment: %s)...' && " +
                        "if kubectl get deployment %s -n %s >/dev/null 2>&1; then " +
                        "  echo '[INFO] Deployment 已存在，更新镜像...' && " +
                        "  CONTAINER=$(kubectl get deployment %s -n %s -o jsonpath='{.spec.template.spec.containers[0].name}') && " +
                        "  kubectl set image deployment/%s $CONTAINER=%s -n %s && " +
                        "  echo '[INFO] ✓ Deployment 镜像已更新' && " +
                        "  kubectl rollout status deployment/%s -n %s --timeout=120s && " +
                        "  echo '[INFO] ✓ 滚动更新完成'; " +
                        "else " +
                        "  echo '[INFO] Deployment 不存在，将由服务端自动创建'; " +
                        "fi",
                deployName, deployName, ns,
                deployName, ns, deployName, fullImage, ns,
                deployName, ns);
        }

        // 构建 Harbor docker config for Kaniko authentication
        // Kaniko 需要 base64 编码的 auth 字段: base64(username:password)
        String authString = harborUsername + ":" + harborPassword;
        String authBase64 = Base64.getEncoder().encodeToString(authString.getBytes());
        String dockerConfigJson = String.format(
                "{\"auths\":{\"%s\":{\"auth\":\"%s\"}}}",
                harborHost, authBase64);
        // 基础镜像 (通过 Harbor 代理缓存,避免 Docker Hub 超时)
        String baseImage = (releaseBaseImage != null && !releaseBaseImage.isEmpty())
                ? releaseBaseImage
                : harborHost + "/library/eclipse-temurin:17-jre-jammy";

        // Build 容器命令: Clone → Maven → Dockerfile.release → Docker Config
        StringBuilder buildCmdBuilder = new StringBuilder();
        // 安装 git
        buildCmdBuilder.append("apt-get update -qq && apt-get install -y -qq git > /dev/null 2>&1 && ");
        buildCmdBuilder.append("echo '[INFO] Git 已安装' && ");
        // Git 配置 (缓解 "remote end hung up" 超时/断连)
        buildCmdBuilder.append("git config --global http.version HTTP/1.1 && ");
        buildCmdBuilder.append("git config --global protocol.version 1 && ");
        buildCmdBuilder.append("git config --global http.postBuffer 524288000 && ");
        buildCmdBuilder.append("git config --global http.lowSpeedLimit 1000 && ");
        buildCmdBuilder.append("git config --global http.lowSpeedTime 120 && ");
        buildCmdBuilder.append("git config --global core.compression 0 && ");
        String effectiveProxy = config.hasGitProxy() ? config.getGitProxy() : globalGitProxy;
        if (effectiveProxy != null && !effectiveProxy.isEmpty()) {
            buildCmdBuilder.append("git config --global http.proxy ").append(effectiveProxy).append(" && ");
            buildCmdBuilder.append("git config --global https.proxy ").append(effectiveProxy).append(" && ");
        }
        // 克隆代码 (合并模式: clone base + 逐个 merge feature, 冲突即中止)
        if (config.isMergeDeploy()) {
            String base = config.getEffectiveBaseBranch();
            buildCmdBuilder.append("git clone --branch ").append(base)
                    .append(" ").append(cloneUrl).append(" /workspace && cd /workspace && ");
            buildCmdBuilder.append("git config user.email 'ci@k3s-demo.local' && ");
            buildCmdBuilder.append("git config user.name 'k3s-demo-ci' && ");
            buildCmdBuilder.append("echo '[MERGE] 基底分支(base): ").append(base).append("' && ");
            for (String feat : config.getNormalizedFeatureBranches()) {
                buildCmdBuilder.append("echo '[MERGE] ➜ 合并分支: ").append(feat).append("' && ");
                buildCmdBuilder.append("git fetch origin ").append(feat).append(" && ");
                buildCmdBuilder.append(String.format(
                        "{ git merge --no-ff --no-edit -m 'ci: merge %s into %s' FETCH_HEAD || "
                                + "{ echo '[CONFLICT] 分支 %s 与当前集成分支存在合并冲突, 冲突文件如下:'; "
                                + "git diff --name-only --diff-filter=U; git merge --abort; exit 1; }; } && ",
                        feat, base, feat));
                buildCmdBuilder.append("echo '[MERGE] ✓ 已合并: ").append(feat).append("' && ");
            }
            buildCmdBuilder.append("echo \"[MERGE] MERGE_COMMIT=$(git rev-parse HEAD)\" && ");
            buildCmdBuilder.append("echo '[INFO] ✓ 多分支合并完成' && ");
        } else {
            buildCmdBuilder.append("git clone --depth 1 --branch ").append(config.getBranch())
                    .append(" ").append(cloneUrl).append(" /workspace && ");
            buildCmdBuilder.append("echo '[INFO] ✓ 代码克隆完成' && ");
        }
        // ===== runtime 感知: 始终产出 /workspace/Dockerfile.release + 写 Harbor 认证 =====
        // 优先级: 仓库自带 Dockerfile → cp + 重写 FROM;否则按 runtime(python 免编译 / java 跑 mvn)
        String runtime = config.resolveRuntime();
        String reqPath = (config.getRequirementsPath() != null && !config.getRequirementsPath().isEmpty())
                ? config.getRequirementsPath() : "requirements.txt";
        String dfUserPath = config.getDockerfilePath() != null ? config.getDockerfilePath() : "./Dockerfile";
        String dfUserFile = dfUserPath.startsWith("./") ? dfUserPath.substring(2) : dfUserPath;
        String dockerConfigBase64 = Base64.getEncoder().encodeToString(dockerConfigJson.getBytes());
        String pyDfRelB64 = b64(buildPythonDockerfile(harborHost, config));
        String javaJarDfB64 = b64(buildJavaJarDockerfile(baseImage));
        String dockerIgnoreB64 = b64(PY_DOCKERIGNORE);

        buildCmdBuilder.append("cd /workspace && ");
        // 写 Harbor 认证(kaniko 推送用),始终执行 — Python/dockerfile 模式 build 容器不跑 mvn, 但此步必须保留
        buildCmdBuilder.append("mkdir -p /docker-config && echo '").append(dockerConfigBase64)
                .append("' | base64 -d > /docker-config/config.json && echo '[INFO] ✓ Harbor 认证已写入' && ");
        buildCmdBuilder.append("RUNTIME='").append(runtime).append("' && REQ_PATH='").append(reqPath)
                .append("' && DFUSER='/workspace/").append(dfUserFile).append("' && ");
        buildCmdBuilder.append("if [ -f \"$DFUSER\" ]; then ");
        buildCmdBuilder.append("  echo '[INFO] 检测到仓库自带 Dockerfile, 优先使用 (重写 FROM 到 Harbor)' && ");
        buildCmdBuilder.append("  cp \"$DFUSER\" /workspace/Dockerfile.release && ");
        buildCmdBuilder.append("  sed -i 's|^FROM docker\\.io/|FROM ").append(harborHost)
                .append("/|; s|^FROM library/|FROM ").append(harborHost).append("/library/|' /workspace/Dockerfile.release && ");
        buildCmdBuilder.append("  sed -i '/^FROM [^/]*$/s|^FROM |FROM ").append(harborHost)
                .append("/library/|' /workspace/Dockerfile.release && ");
        buildCmdBuilder.append("  echo '[INFO] Dockerfile.release:' && cat /workspace/Dockerfile.release; ");
        buildCmdBuilder.append("else ");
        buildCmdBuilder.append("  if [ \"$RUNTIME\" = 'auto' ] || [ -z \"$RUNTIME\" ]; then ");
        buildCmdBuilder.append("    HAS_PY=0; HAS_JAVA=0; ");
        buildCmdBuilder.append("    if [ -f \"/workspace/$REQ_PATH\" ] || [ -f /workspace/requirements.txt ]; then HAS_PY=1; fi; ");
        buildCmdBuilder.append("    if [ -f /workspace/pom.xml ] || [ -f /workspace/build.gradle ]; then HAS_JAVA=1; fi; ");
        buildCmdBuilder.append("    if [ \"$HAS_PY\" = 1 ] && [ \"$HAS_JAVA\" = 1 ]; then echo '[ERROR] 同时检测到 Java(pom.xml/build.gradle) 与 Python(requirements.txt) 构建文件, 无法自动判定语言; 请在表单显式选择 runtime=java 或 python(避免 Python/Java 流水线混淆)' && exit 1; ");
        buildCmdBuilder.append("    elif [ \"$HAS_PY\" = 1 ]; then RUNTIME=python; ");
        buildCmdBuilder.append("    elif [ \"$HAS_JAVA\" = 1 ]; then RUNTIME=java; ");
        buildCmdBuilder.append("    elif [ -f /workspace/pyproject.toml ] || [ -f /workspace/Pipfile ]; then echo '[ERROR] 检测到 pyproject/Pipfile 但缺少 requirements.txt; 本期未支持 Poetry/Pipenv, 请先 poetry export -f requirements.txt -o requirements.txt 或自带 Dockerfile' && exit 1; ");
        buildCmdBuilder.append("    else echo '[ERROR] 无法识别项目类型, 请显式指定 runtime(java/python) 或自带 Dockerfile' && exit 1; fi; ");
        buildCmdBuilder.append("  fi; ");
        buildCmdBuilder.append("  if [ \"$RUNTIME\" = 'dockerfile' ]; then echo '[ERROR] runtime=dockerfile 但仓库未发现 Dockerfile' && exit 1; fi; ");
        buildCmdBuilder.append("  echo \"[INFO] 构建运行时: $RUNTIME\" && ");
        buildCmdBuilder.append("  if [ \"$RUNTIME\" = 'python' ]; then ");
        buildCmdBuilder.append("    if [ ! -f \"/workspace/$REQ_PATH\" ]; then echo '# auto-created empty requirements' > \"/workspace/$REQ_PATH\" && echo \"[INFO] 未发现 $REQ_PATH, 使用空依赖清单\"; fi && ");
        buildCmdBuilder.append("    echo '").append(pyDfRelB64).append("' | base64 -d > /workspace/Dockerfile.release && ");
        buildCmdBuilder.append("    echo '").append(dockerIgnoreB64).append("' | base64 -d > /workspace/.dockerignore && ");
        buildCmdBuilder.append("    echo '[INFO] ✓ 已生成 Python Dockerfile.release:' && cat /workspace/Dockerfile.release; ");
        buildCmdBuilder.append("  else ");
        buildCmdBuilder.append("    echo '[INFO] Java: 执行 Maven 打包...' && ").append(buildCmd)
                .append(" && echo '[INFO] ✓ Maven 构建完成' && ls -la /workspace/target/*.jar 2>/dev/null && ");
        buildCmdBuilder.append("    echo '").append(javaJarDfB64).append("' | base64 -d > /workspace/Dockerfile.release && ");
        buildCmdBuilder.append("    echo '[INFO] ✓ 已生成 Java Dockerfile.release:' && cat /workspace/Dockerfile.release; ");
        buildCmdBuilder.append("  fi; ");
        buildCmdBuilder.append("fi");

        return new JobBuilder()
                .withNewMetadata()
                .withName(jobName)
                .withNamespace("default")
                .addToLabels("release-pipeline", releaseId)
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(0)
                .withTtlSecondsAfterFinished(3600) // 1小时后自动清理
                .withNewTemplate()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withServiceAccountName("default")
                // Harbor 域名解析 (hostAliases → /etc/hosts)
                .addNewHostAlias()
                .withIp(harborIp != null && !harborIp.isEmpty() ? harborIp : "127.0.0.1")
                .withHostnames(harborHost.contains(":") ? harborHost.split(":")[0] : harborHost)
                .endHostAlias()

                // ===== Init Container 1: Build (Clone + Maven + Dockerfile + Docker Config)
                // =====
                .addNewInitContainer()
                .withName("build")
                .withImage(mavenImage)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("sh", "-c", buildCmdBuilder.toString())
                .addNewVolumeMount()
                .withName("workspace")
                .withMountPath("/workspace")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("maven-repo")
                .withMountPath("/root/.m2")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("docker-config")
                .withMountPath("/docker-config")
                .endVolumeMount()
                .endInitContainer()

                // ===== Init Container 2: Kaniko Build & Push to Harbor =====
                // 使用生成的 Dockerfile.release (runtime-only), 直接打包 JAR
                .addNewInitContainer()
                .withName("kaniko-build")
                .withImage(kanikoImage)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/kaniko/executor")
                .withArgs(
                        "--dockerfile=/workspace/Dockerfile.release",
                        "--context=dir:///workspace",
                        "--destination=" + fullImage,
                        "--insecure",
                        "--skip-tls-verify",
                        "--cache=true",
                        "--cache-repo=" + harborHost + "/" + harborProject + "/kaniko-cache",
                        "--verbosity=info")
                .withNewResources()
                .addToRequests("cpu", new Quantity("500m"))
                .addToRequests("memory", new Quantity("1Gi"))
                .addToLimits("cpu", new Quantity("2"))
                .addToLimits("memory", new Quantity("4Gi"))
                .endResources()
                .addNewVolumeMount()
                .withName("workspace")
                .withMountPath("/workspace")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("docker-config")
                .withMountPath("/kaniko/.docker")
                .endVolumeMount()
                .endInitContainer()

                // ===== Main Container: Deployer (kubectl) =====
                .addNewContainer()
                .withName("deployer")
                .withImage("bitnami/kubectl:latest")
                .withImagePullPolicy("IfNotPresent")
                .withCommand("sh", "-c", deployCmd)
                .endContainer()

                // ===== Volumes =====
                .addNewVolume()
                .withName("workspace")
                .withNewEmptyDir().endEmptyDir()
                .endVolume()
                .addNewVolume()
                .withName("maven-repo")
                .withNewPersistentVolumeClaim()
                .withClaimName("maven-repo-pvc")
                .endPersistentVolumeClaim()
                .endVolume()
                .addNewVolume()
                .withName("docker-config")
                .withNewEmptyDir().endEmptyDir()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    // ==================== Pod & Container 辅助方法 ====================

    private String waitForPodName(KubernetesClient client, String jobName, ReleaseRecord record)
            throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            List<Pod> pods = client.pods().inNamespace("default")
                    .withLabel("job-name", jobName).list().getItems();
            if (!pods.isEmpty()) {
                Pod pod = pods.get(0);
                if ("Failed".equals(pod.getStatus().getPhase())) {
                    record.addLog("[ERROR] Pod 启动失败");
                    broadcastLog(record);
                    return null;
                }
                String detail = getPodWaitingReason(pod);
                if (detail != null && (detail.contains("ImagePullBackOff") || detail.contains("ErrImagePull"))) {
                    record.addLog("[ERROR] 镜像拉取失败: " + detail);
                    broadcastLog(record);
                    return null;
                }
                return pod.getMetadata().getName();
            }
            if (i % 5 == 0 && i > 0) {
                record.addLog("[INFO] 等待 Pod 创建...");
                broadcastLog(record);
            }
            Thread.sleep(5000);
        }
        return null;
    }

    private boolean waitForInitContainerAndStreamLogs(KubernetesClient client, String podName,
            String containerName, ReleaseRecord record) {
        try {
            int lastLineCount = 0;
            for (int i = 0; i < 360; i++) { // up to 30 min
                Pod pod = client.pods().inNamespace("default").withName(podName).get();
                if (pod == null)
                    return false;
                String containerState = getInitContainerState(pod, containerName);

                if ("running".equals(containerState) || "terminated".equals(containerState)) {
                    try {
                        String logs = client.pods().inNamespace("default").withName(podName)
                                .inContainer(containerName).getLog();
                        if (logs != null && !logs.isEmpty()) {
                            String[] lines = logs.split("\n");
                            for (int j = lastLineCount; j < lines.length; j++) {
                                record.addLog(lines[j]);
                            }
                            if (lines.length > lastLineCount) {
                                lastLineCount = lines.length;
                                broadcastLog(record);
                            }
                        }
                    } catch (Exception logErr) {
                        // transient, continue polling
                    }

                    if ("terminated".equals(containerState)) {
                        return checkInitContainerSucceeded(client, podName, containerName, record);
                    }
                }

                if ("Failed".equals(pod.getStatus().getPhase()))
                    return false;

                if (checkAnyInitContainerFailed(pod, record))
                    return false;

                if ("waiting".equals(containerState)) {
                    String reason = getInitContainerWaitingReason(pod, containerName);
                    if (reason != null) {
                        if (reason.contains("ImagePullBackOff") || reason.contains("ErrImagePull")) {
                            record.addLog("[ERROR] " + containerName + " 镜像拉取失败: " + reason);
                            broadcastLog(record);
                            return false;
                        }
                        if (reason.contains("CrashLoopBackOff")) {
                            record.addLog("[ERROR] " + containerName + " 反复崩溃");
                            broadcastLog(record);
                            return false;
                        }
                    }
                    if (i % 6 == 0 && i > 0) {
                        record.addLog("[INFO] " + containerName + " 等待中" +
                                (reason != null ? " (" + reason + ")" : "") + "...");
                        broadcastLog(record);
                    }
                }

                if ("Running".equals(pod.getStatus().getPhase())) {
                    try {
                        String logs = client.pods().inNamespace("default").withName(podName)
                                .inContainer(containerName).getLog();
                        if (logs != null && !logs.isEmpty()) {
                            String[] lines = logs.split("\n");
                            for (int j = lastLineCount; j < lines.length; j++) {
                                record.addLog(lines[j]);
                            }
                            if (lines.length > lastLineCount)
                                broadcastLog(record);
                        }
                    } catch (Exception ignored) {
                    }
                    return checkInitContainerSucceeded(client, podName, containerName, record);
                }

                Thread.sleep(5000);
            }
            record.addLog("[ERROR] " + containerName + " 执行超时 (30分钟)");
            broadcastLog(record);
            return false;
        } catch (Exception e) {
            record.addLog("[ERROR] " + containerName + " 异常: " + e.getMessage());
            broadcastLog(record);
            return false;
        }
    }

    private String getInitContainerState(Pod pod, String containerName) {
        var initStatuses = pod.getStatus().getInitContainerStatuses();
        if (initStatuses != null) {
            for (var cs : initStatuses) {
                if (containerName.equals(cs.getName()) && cs.getState() != null) {
                    if (cs.getState().getTerminated() != null)
                        return "terminated";
                    if (cs.getState().getRunning() != null)
                        return "running";
                    if (cs.getState().getWaiting() != null)
                        return "waiting";
                }
            }
        }
        return null;
    }

    private String getInitContainerWaitingReason(Pod pod, String containerName) {
        var initStatuses = pod.getStatus().getInitContainerStatuses();
        if (initStatuses != null) {
            for (var cs : initStatuses) {
                if (containerName.equals(cs.getName()) && cs.getState() != null
                        && cs.getState().getWaiting() != null) {
                    return cs.getState().getWaiting().getReason();
                }
            }
        }
        return null;
    }

    private boolean checkInitContainerSucceeded(KubernetesClient client, String podName,
            String containerName, ReleaseRecord record) {
        try {
            Pod pod = client.pods().inNamespace("default").withName(podName).get();
            if (pod == null)
                return false;
            var initStatuses = pod.getStatus().getInitContainerStatuses();
            if (initStatuses != null) {
                for (var cs : initStatuses) {
                    if (containerName.equals(cs.getName()) && cs.getState() != null
                            && cs.getState().getTerminated() != null) {
                        int exitCode = cs.getState().getTerminated().getExitCode();
                        if (exitCode == 0)
                            return true;
                        String reason = cs.getState().getTerminated().getReason();
                        record.addLog("[ERROR] " + containerName + " 失败 (exit=" + exitCode + "): " +
                                diagnoseExitCode(exitCode, reason));
                        broadcastLog(record);
                        return false;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean checkAnyInitContainerFailed(Pod pod, ReleaseRecord record) {
        var initStatuses = pod.getStatus().getInitContainerStatuses();
        if (initStatuses != null) {
            for (var cs : initStatuses) {
                if (cs.getState() != null && cs.getState().getTerminated() != null) {
                    int exitCode = cs.getState().getTerminated().getExitCode();
                    if (exitCode != 0) {
                        String reason = cs.getState().getTerminated().getReason();
                        record.addLog(String.format("[ERROR] 前置容器 %s 失败 (exit %d): %s",
                                cs.getName(), exitCode, reason));
                        broadcastLog(record);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean waitForPodRunning(KubernetesClient client, String podName, ReleaseRecord record)
            throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Pod pod = client.pods().inNamespace("default").withName(podName).get();
            if (pod == null)
                return false;
            String phase = pod.getStatus().getPhase();
            if ("Running".equals(phase) || "Succeeded".equals(phase))
                return true;
            if ("Failed".equals(phase))
                return false;
            if (i % 5 == 0 && i > 0) {
                record.addLog("[INFO] 等待部署容器启动...");
                broadcastLog(record);
            }
            Thread.sleep(5000);
        }
        return false;
    }

    private void streamContainerLogs(KubernetesClient client, String podName, String namespace,
            String containerName, ReleaseRecord record) {
        try {
            int lastLineCount = 0;
            for (int i = 0; i < 120; i++) {
                Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
                if (pod == null)
                    return;

                String phase = pod.getStatus().getPhase();
                if ("Failed".equals(phase)) {
                    record.addLog("[ERROR] Pod 状态为 Failed");
                    broadcastLog(record);
                    return;
                }

                boolean containerReady = false;
                boolean containerTerminated = false;
                if (pod.getStatus().getContainerStatuses() != null) {
                    for (var cs : pod.getStatus().getContainerStatuses()) {
                        if (containerName.equals(cs.getName()) && cs.getState() != null) {
                            if (cs.getState().getRunning() != null)
                                containerReady = true;
                            if (cs.getState().getTerminated() != null) {
                                containerReady = true;
                                containerTerminated = true;
                            }
                        }
                    }
                }

                if (containerReady) {
                    try {
                        String logs = client.pods().inNamespace(namespace).withName(podName)
                                .inContainer(containerName).getLog();
                        if (logs != null && !logs.isEmpty()) {
                            String[] lines = logs.split("\n");
                            for (int j = lastLineCount; j < lines.length; j++) {
                                record.addLog(lines[j]);
                            }
                            if (lines.length > lastLineCount) {
                                lastLineCount = lines.length;
                                broadcastLog(record);
                            }
                        }
                    } catch (Exception logErr) {
                        // transient
                    }
                }

                if (containerTerminated || "Succeeded".equals(phase))
                    return;
                Thread.sleep(5000);
            }
        } catch (Exception e) {
            record.addLog("[WARN] " + containerName + " 日志流结束");
        }
    }

    private boolean waitForJobCompletion(KubernetesClient client, String jobName, ReleaseRecord record)
            throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            Job job = client.batch().v1().jobs().inNamespace("default").withName(jobName).get();
            if (job == null) {
                record.addLog("[ERROR] Job 不存在: " + jobName);
                return false;
            }
            var jobStatus = job.getStatus();
            if (jobStatus != null) {
                if (jobStatus.getSucceeded() != null && jobStatus.getSucceeded() > 0)
                    return true;
                if (jobStatus.getFailed() != null && jobStatus.getFailed() > 0)
                    return false;
            }
            Thread.sleep(5000);
        }
        record.addLog("[ERROR] Job 执行超时");
        return false;
    }

    /**
     * 确保 Service 存在，用于暴露 Deployment 供访问（NodePort）。
     */
    private void ensureService(KubernetesClient client, String namespace, String deployName, int appPort,
            ReleaseRecord record) {
        String svcName = deployName;
        try {
            io.fabric8.kubernetes.api.model.Service existing = client.services().inNamespace(namespace).withName(svcName).get();
            if (existing == null) {
                io.fabric8.kubernetes.api.model.Service svc = new io.fabric8.kubernetes.api.model.ServiceBuilder()
                        .withNewMetadata()
                        .withName(svcName)
                        .withNamespace(namespace)
                        .addToLabels("app", deployName)
                        .endMetadata()
                        .withNewSpec()
                        .withType("NodePort")
                        .addToSelector("app", deployName)
                        .addNewPort()
                        .withName("http")
                        .withProtocol("TCP")
                        .withPort(appPort)
                        .withNewTargetPort(appPort)
                        .endPort()
                        .endSpec()
                        .build();
                client.services().inNamespace(namespace).resource(svc).create();
                io.fabric8.kubernetes.api.model.Service created = client.services().inNamespace(namespace).withName(svcName).get();
                Integer nodePort = created != null && created.getSpec() != null && !created.getSpec().getPorts().isEmpty()
                        ? created.getSpec().getPorts().get(0).getNodePort() : null;
                record.addLog("[INFO] ✓ Service 已创建: " + svcName + " (NodePort: " + (nodePort != null ? nodePort : "自动分配") + ", 访问: http://节点IP:" + (nodePort != null ? nodePort : "NodePort") + ")");
                broadcastLog(record);
            }
        } catch (Exception e) {
            record.addLog("[WARN] Service 创建/检查失败: " + e.getMessage());
            broadcastLog(record);
        }
    }

    /**
     * 确保 Harbor 镜像拉取 Secret 存在，如果不存在则创建。
     */
    private void ensureHarborSecret(KubernetesClient client, String namespace, ReleaseRecord record) {
        String secretName = "harbor-registry-secret";
        try {
            Secret secret = client.secrets().inNamespace(namespace).withName(secretName).get();
            if (secret == null) {
                // 创建 Harbor Docker Registry Secret
                String authString = harborUsername + ":" + harborPassword;
                String authBase64 = Base64.getEncoder().encodeToString(authString.getBytes());
                String dockerConfigJson = String.format(
                        "{\"auths\":{\"%s\":{\"auth\":\"%s\"}}}",
                        harborHost, authBase64);
                String dockerConfigBase64 = Base64.getEncoder().encodeToString(dockerConfigJson.getBytes());

                secret = new SecretBuilder()
                        .withNewMetadata()
                        .withName(secretName)
                        .withNamespace(namespace)
                        .endMetadata()
                        .withType("kubernetes.io/dockerconfigjson")
                        .addToData(".dockerconfigjson", dockerConfigBase64)
                        .build();

                client.secrets().inNamespace(namespace).resource(secret).create();
                record.addLog("[INFO] ✓ Harbor Secret 已创建: " + secretName);
                broadcastLog(record);
            }
        } catch (Exception e) {
            record.addLog("[WARN] Harbor Secret 创建/检查失败: " + e.getMessage());
            broadcastLog(record);
        }
    }

    private void deployToK3s(KubernetesClient client, ReleaseConfig config, String fullImage, ReleaseRecord record) {
        try {
            String ns = config.getNamespace();
            String deployName = config.getDeploymentName();

            // 确保 Harbor Secret 存在
            ensureHarborSecret(client, ns, record);

            Deployment deployment = client.apps().deployments()
                    .inNamespace(ns).withName(deployName).get();

            if (deployment == null) {
                record.addLog("[INFO] Deployment 不存在: " + deployName + ", 正在自动创建...");
                broadcastLog(record);
                
                // 自动创建基本的 Deployment（包含 Harbor imagePullSecrets）
                DeploymentBuilder deploymentBuilder = new DeploymentBuilder()
                        .withNewMetadata()
                        .withName(deployName)
                        .withNamespace(ns)
                        .addToLabels("app", deployName)
                        .endMetadata()
                        .withNewSpec()
                        .withReplicas(1)
                        .withNewSelector()
                        .addToMatchLabels("app", deployName)
                        .endSelector()
                        .withNewTemplate()
                        .withNewMetadata()
                        .addToLabels("app", deployName)
                        .endMetadata()
                        .withNewSpec()
                        .addNewImagePullSecret("harbor-registry-secret")
                        .addNewContainer()
                        .withName(deployName + "-container")
                        .withImage(fullImage)
                        .withImagePullPolicy("Always")
                        .addNewPort()
                        .withContainerPort(config.getEffectiveAppPort())
                        .withName("http")
                        .withProtocol("TCP")
                        .endPort()
                        .withNewResources()
                        .addToRequests("cpu", new Quantity("100m"))
                        .addToRequests("memory", new Quantity("256Mi"))
                        .addToLimits("cpu", new Quantity("500m"))
                        .addToLimits("memory", new Quantity("512Mi"))
                        .endResources()
                        .endContainer()
                        .endSpec()
                        .endTemplate()
                        .endSpec();
                
                deployment = deploymentBuilder.build();
                client.apps().deployments().inNamespace(ns).resource(deployment).create();
                record.addLog("[INFO] ✓ Deployment 已创建: " + deployName + " (镜像: " + fullImage + ")");
                broadcastLog(record);
                ensureService(client, ns, deployName, config.getEffectiveAppPort(), record);
                return;
            }

            // 更新现有 Deployment
            var podSpec = deployment.getSpec().getTemplate().getSpec();
            var container = podSpec.getContainers().get(0);
            container.setImage(fullImage);
            container.setImagePullPolicy("Always");
            
            // 确保 imagePullSecrets 存在
            if (podSpec.getImagePullSecrets() == null || podSpec.getImagePullSecrets().isEmpty()) {
                LocalObjectReference imagePullSecret = new LocalObjectReference();
                imagePullSecret.setName("harbor-registry-secret");
                podSpec.setImagePullSecrets(Collections.singletonList(imagePullSecret));
                record.addLog("[INFO] 已添加 Harbor imagePullSecrets 到现有 Deployment");
                broadcastLog(record);
            }

            client.apps().deployments().inNamespace(ns).resource(deployment).update();
            record.addLog("[INFO] ✓ Deployment 已更新: " + deployName + " → " + fullImage);

            ensureService(client, ns, deployName, config.getEffectiveAppPort(), record);

            record.addLog("[INFO] 等待滚动更新...");
            broadcastLog(record);
            Thread.sleep(3000);

            Deployment updated = client.apps().deployments()
                    .inNamespace(ns).withName(deployName).get();
            if (updated != null && updated.getStatus() != null) {
                int desired = updated.getSpec().getReplicas() != null ? updated.getSpec().getReplicas() : 1;
                int ready = updated.getStatus().getReadyReplicas() != null ? updated.getStatus().getReadyReplicas() : 0;
                record.addLog("[INFO] 副本状态: " + ready + "/" + desired + " Ready");
            }
            broadcastLog(record);
        } catch (Exception e) {
            record.addLog("[ERROR] 部署更新失败: " + e.getMessage());
            broadcastLog(record);
        }
    }

    /**
     * 合并预览部署 (Harbor 模式): 把推送到 Harbor 的镜像部署到独立预览命名空间 preview-&lt;mergeSetId&gt;。
     * 在预览命名空间内创建 Harbor 拉取 Secret + Deployment(imagePullSecrets) + NodePort Service。
     */
    private void deployToPreview(KubernetesClient client, ReleaseConfig config, String fullImage,
            ReleaseRecord record) {
        try {
            String previewNs = config.getPreviewNamespace();
            record.setPreviewNamespace(previewNs);
            String appName = sanitizeName(config.getImageName()) + "-preview";
            int appPort = config.getEffectiveAppPort();
            long nowMs = System.currentTimeMillis();

            // 1. 幂等创建预览命名空间
            Namespace existingNs = client.namespaces().withName(previewNs).get();
            if (existingNs == null) {
                Namespace ns = new NamespaceBuilder()
                        .withNewMetadata()
                        .withName(previewNs)
                        .addToLabels("managed-by", "k3s-demo-devops")
                        .addToLabels("preview-env", "true")
                        .addToLabels("merge-set-id", config.getMergeSetId())
                        .addToAnnotations("k3s-demo/created-at", String.valueOf(nowMs))
                        .addToAnnotations("k3s-demo/ttl-minutes", String.valueOf(config.getPreviewTtlMinutes()))
                        .addToAnnotations("k3s-demo/base-branch", config.getEffectiveBaseBranch())
                        .addToAnnotations("k3s-demo/feature-branches",
                                String.join(",", config.getNormalizedFeatureBranches()))
                        .endMetadata()
                        .build();
                client.namespaces().resource(ns).create();
                record.addLog("[INFO] ✓ 已创建预览命名空间: " + previewNs);
            } else {
                record.addLog("[INFO] 复用已存在的预览命名空间: " + previewNs + " (续期 TTL)");
                try {
                    client.namespaces().withName(previewNs).edit(n -> new NamespaceBuilder(n)
                            .editMetadata()
                            .addToAnnotations("k3s-demo/created-at", String.valueOf(nowMs))
                            .endMetadata().build());
                } catch (Exception ignore) {
                }
            }
            broadcastLog(record);

            // 2. 预览命名空间内创建 Harbor 拉取 Secret
            ensureHarborSecret(client, previewNs, record);

            // 3. 创建/更新 Deployment (从 Harbor 拉取, imagePullSecrets)
            Deployment existing = client.apps().deployments().inNamespace(previewNs).withName(appName).get();
            if (existing == null) {
                Deployment desired = new DeploymentBuilder()
                        .withNewMetadata()
                        .withName(appName)
                        .withNamespace(previewNs)
                        .addToLabels("app", appName)
                        .addToLabels("preview-env", "true")
                        .endMetadata()
                        .withNewSpec()
                        .withReplicas(1)
                        .withNewSelector().addToMatchLabels("app", appName).endSelector()
                        .withNewTemplate()
                        .withNewMetadata().addToLabels("app", appName).endMetadata()
                        .withNewSpec()
                        .addNewImagePullSecret("harbor-registry-secret")
                        .addNewContainer()
                        .withName("app")
                        .withImage(fullImage)
                        .withImagePullPolicy("Always")
                        .addNewPort().withContainerPort(appPort).endPort()
                        .endContainer()
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build();
                client.apps().deployments().inNamespace(previewNs).resource(desired).create();
                record.addLog("[INFO] ✓ 已创建预览 Deployment: " + appName);
            } else {
                var container = existing.getSpec().getTemplate().getSpec().getContainers().get(0);
                container.setImage(fullImage);
                container.setImagePullPolicy("Always");
                client.apps().deployments().inNamespace(previewNs).resource(existing).update();
                record.addLog("[INFO] ✓ 已更新预览 Deployment: " + appName + " -> " + fullImage);
            }
            broadcastLog(record);

            // 4. 创建/更新 NodePort Service (自动分配端口)
            io.fabric8.kubernetes.api.model.Service existingSvc = client.services().inNamespace(previewNs)
                    .withName(appName).get();
            if (existingSvc == null) {
                io.fabric8.kubernetes.api.model.Service svc = new io.fabric8.kubernetes.api.model.ServiceBuilder()
                        .withNewMetadata()
                        .withName(appName)
                        .withNamespace(previewNs)
                        .addToLabels("app", appName)
                        .addToLabels("preview-env", "true")
                        .endMetadata()
                        .withNewSpec()
                        .withType("NodePort")
                        .addToSelector("app", appName)
                        .addNewPort()
                        .withName("http")
                        .withProtocol("TCP")
                        .withPort(appPort)
                        .withNewTargetPort(appPort)
                        .endPort()
                        .endSpec()
                        .build();
                client.services().inNamespace(previewNs).resource(svc).create();
                record.addLog("[INFO] ✓ 已创建预览 Service (NodePort 自动分配): " + appName);
            } else {
                record.addLog("[INFO] 复用预览 Service: " + appName);
            }
            broadcastLog(record);

            // 5. 回填访问地址
            io.fabric8.kubernetes.api.model.Service svc = client.services().inNamespace(previewNs)
                    .withName(appName).get();
            Integer nodePort = null;
            if (svc != null && svc.getSpec() != null && svc.getSpec().getPorts() != null
                    && !svc.getSpec().getPorts().isEmpty()) {
                nodePort = svc.getSpec().getPorts().get(0).getNodePort();
            }
            String nodeIp = getFirstNodeIp(client);
            if (nodePort != null && nodeIp != null) {
                String url = "http://" + nodeIp + ":" + nodePort;
                record.setPreviewNodePortUrl(url);
                record.addLog("[INFO] ✓ 预览环境访问地址: " + url);
            } else if (nodePort != null) {
                record.setPreviewNodePortUrl("http://<节点IP>:" + nodePort);
                record.addLog("[INFO] ✓ 预览环境 NodePort: " + nodePort);
            }
            broadcastLog(record);

            Thread.sleep(3000);
            Deployment updated = client.apps().deployments().inNamespace(previewNs).withName(appName).get();
            if (updated != null && updated.getStatus() != null) {
                int desiredR = updated.getSpec().getReplicas() != null ? updated.getSpec().getReplicas() : 1;
                int ready = updated.getStatus().getReadyReplicas() != null ? updated.getStatus().getReadyReplicas() : 0;
                record.addLog("[INFO] 预览副本状态: " + ready + "/" + desiredR + " Ready");
            }
            broadcastLog(record);
        } catch (Exception e) {
            record.addLog("[ERROR] 预览环境部署失败: " + e.getMessage());
            broadcastLog(record);
        }
    }

    /**
     * 从日志中解析合并结果: merge commit sha 与冲突文件列表。
     */
    private void parseMergeResults(ReleaseRecord record) {
        boolean inConflict = false;
        for (String raw : new ArrayList<>(record.getLogs())) {
            String content = raw;
            if (content.startsWith("[") && content.length() > 11 && content.charAt(9) == ']') {
                content = content.substring(11);
            }
            content = content.trim();
            if (content.startsWith("[MERGE] MERGE_COMMIT=")) {
                String sha = content.substring("[MERGE] MERGE_COMMIT=".length()).trim();
                if (!sha.isEmpty() && !sha.startsWith("$")) {
                    record.setMergeCommitSha(sha.length() > 12 ? sha.substring(0, 12) : sha);
                }
                inConflict = false;
            } else if (content.contains("[CONFLICT]")) {
                inConflict = true;
            } else if (inConflict) {
                if (content.isEmpty() || content.startsWith("[") || content.startsWith("===")
                        || content.startsWith("CONFLICT")) {
                    inConflict = false;
                } else if (!record.getConflictFiles().contains(content)) {
                    record.addConflictFile(content);
                }
            }
        }
    }

    private String sanitizeName(String raw) {
        if (raw == null) {
            return "app";
        }
        String s = raw.toLowerCase()
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return s.isEmpty() ? "app" : s;
    }

    private String getFirstNodeIp(KubernetesClient client) {
        try {
            var nodes = client.nodes().list().getItems();
            for (var node : nodes) {
                if (node.getStatus() != null && node.getStatus().getAddresses() != null) {
                    String external = null;
                    String internal = null;
                    for (var addr : node.getStatus().getAddresses()) {
                        if ("ExternalIP".equals(addr.getType())) {
                            external = addr.getAddress();
                        }
                        if ("InternalIP".equals(addr.getType())) {
                            internal = addr.getAddress();
                        }
                    }
                    if (external != null) {
                        return external;
                    }
                    if (internal != null) {
                        return internal;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String getPodWaitingReason(Pod pod) {
        try {
            var statuses = pod.getStatus().getContainerStatuses();
            if (statuses == null || statuses.isEmpty()) {
                statuses = pod.getStatus().getInitContainerStatuses();
            }
            if (statuses != null) {
                for (var cs : statuses) {
                    var waiting = cs.getState() != null ? cs.getState().getWaiting() : null;
                    if (waiting != null && waiting.getReason() != null) {
                        return waiting.getReason();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void cleanupJob(KubernetesClient client, String jobName) {
        try {
            client.batch().v1().jobs().inNamespace("default").withName(jobName)
                    .withPropagationPolicy(DeletionPropagation.BACKGROUND).delete();
        } catch (Exception e) {
            // ignore
        }
    }

    private String diagnoseExitCode(int exitCode, String reason) {
        if ("OOMKilled".equals(reason) || exitCode == 137) {
            return "内存溢出 (OOM), 请增加容器内存限制";
        }
        return switch (exitCode) {
            case 1 -> "脚本执行失败, 请检查构建命令或代码";
            case 2 -> "Shell 语法错误";
            case 126 -> "命令无法执行 (权限不足)";
            case 127 -> "命令未找到, 请检查镜像";
            case 137 -> "被 SIGKILL 终止 (可能 OOM)";
            case 143 -> "收到 SIGTERM 终止信号";
            default -> "错误 (exit " + exitCode + ")" + (reason != null ? ", " + reason : "");
        };
    }

    private void diagnoseMainContainerFailure(KubernetesClient client, String jobName, ReleaseRecord record) {
        try {
            List<Pod> pods = client.pods().inNamespace("default")
                    .withLabel("job-name", jobName).list().getItems();
            if (pods.isEmpty()) {
                record.addLog("[WARN] 未找到 Pod，无法进行诊断");
                broadcastLog(record);
                return;
            }
            Pod pod = pods.get(0);
            String podName = pod.getMetadata().getName();
            String podPhase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";

            record.addLog("=== 错误诊断 (" + podName + ") ===");
            record.addLog("[INFO] Pod 状态: " + podPhase);
            broadcastLog(record);

            // 检查 Init Containers
            var initStatuses = pod.getStatus().getInitContainerStatuses();
            if (initStatuses != null && !initStatuses.isEmpty()) {
                record.addLog("[INFO] 检查 Init Containers...");
                broadcastLog(record);
                for (var cs : initStatuses) {
                    if (cs.getState() != null && cs.getState().getTerminated() != null) {
                        int exitCode = cs.getState().getTerminated().getExitCode();
                        if (exitCode != 0) {
                            record.addLog("❌ [Init Container: " + cs.getName() + "] exit=" + exitCode + ": " +
                                    diagnoseExitCode(exitCode, cs.getState().getTerminated().getReason()));
                            try {
                                String logs = client.pods().inNamespace("default").withName(podName)
                                        .inContainer(cs.getName()).tailingLines(20).getLog();
                                if (logs != null && !logs.trim().isEmpty()) {
                                    record.addLog("最后20行日志:");
                                    String[] logLines = logs.split("\n");
                                    for (String line : logLines) {
                                        record.addLog("  " + line);
                                    }
                                }
                            } catch (Exception e) {
                                record.addLog("[WARN] 无法获取 " + cs.getName() + " 日志: " + e.getMessage());
                            }
                        } else {
                            record.addLog("✓ [Init Container: " + cs.getName() + "] 执行成功");
                        }
                    } else if (cs.getState() != null && cs.getState().getWaiting() != null) {
                        String reason = cs.getState().getWaiting().getReason();
                        record.addLog("⏳ [Init Container: " + cs.getName() + "] 等待中: " + reason);
                    }
                }
            }

            // 检查主容器（deployer）
            var containerStatuses = pod.getStatus().getContainerStatuses();
            if (containerStatuses != null && !containerStatuses.isEmpty()) {
                record.addLog("[INFO] 检查主容器...");
                broadcastLog(record);
                for (var cs : containerStatuses) {
                    if (cs.getState() != null && cs.getState().getTerminated() != null) {
                        int exitCode = cs.getState().getTerminated().getExitCode();
                        String reason = cs.getState().getTerminated().getReason();
                        record.addLog("❌ [主容器: " + cs.getName() + "] exit=" + exitCode + ": " +
                                diagnoseExitCode(exitCode, reason));
                        try {
                            String logs = client.pods().inNamespace("default").withName(podName)
                                    .inContainer(cs.getName()).tailingLines(50).getLog();
                            if (logs != null && !logs.trim().isEmpty()) {
                                record.addLog("最后50行日志:");
                                String[] logLines = logs.split("\n");
                                for (String line : logLines) {
                                    record.addLog("  " + line);
                                }
                            }
                        } catch (Exception e) {
                            record.addLog("[WARN] 无法获取 " + cs.getName() + " 日志: " + e.getMessage());
                        }
                    } else if (cs.getState() != null && cs.getState().getWaiting() != null) {
                        String reason = cs.getState().getWaiting().getReason();
                        String message = cs.getState().getWaiting().getMessage();
                        record.addLog("⏳ [主容器: " + cs.getName() + "] 等待中: " + reason +
                                (message != null ? " (" + message + ")" : ""));
                    } else if (cs.getState() != null && cs.getState().getRunning() != null) {
                        record.addLog("✓ [主容器: " + cs.getName() + "] 运行中");
                    }
                }
            }

            // 检查 Pod 事件（如果有）
            try {
                record.addLog("[INFO] 检查 Pod 事件...");
                broadcastLog(record);
                var events = client.v1().events().inNamespace("default")
                        .withField("involvedObject.name", podName)
                        .withField("involvedObject.kind", "Pod")
                        .list().getItems();
                if (!events.isEmpty()) {
                    // 只显示最近的错误事件
                    events.stream()
                            .filter(e -> "Warning".equals(e.getType()))
                            .sorted((a, b) -> b.getFirstTimestamp().compareTo(a.getFirstTimestamp()))
                            .limit(5)
                            .forEach(e -> record.addLog("⚠️  事件: " + e.getReason() + " - " + e.getMessage()));
                }
            } catch (Exception e) {
                // 忽略事件获取失败
            }

            record.addLog("=== 诊断完成 ===");
            broadcastLog(record);
        } catch (Exception e) {
            record.addLog("[ERROR] 诊断失败: " + e.getMessage());
            e.printStackTrace();
            broadcastLog(record);
        }
    }

    // ==================== 超时清理 ====================

    @Scheduled(fixedRate = 60000)
    public void sweepStaleReleases() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30);
        for (Map.Entry<String, ReleaseRecord> entry : releases.entrySet()) {
            ReleaseRecord record = entry.getValue();
            if (!record.isFinished() && record.getLastActivityTime().isBefore(cutoff)) {
                record.addLog("[WARN] 发布超过 30 分钟无活动, 强制终止");
                record.fail("超时被系统终止");
                broadcastStatus(record);
                broadcastLog(record);
                completeEmitters(record.getId());
                String jobName = "release-" + record.getId();
                try (KubernetesClient client = new KubernetesClientBuilder().build()) {
                    cleanupJob(client, jobName);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ==================== SSE Emitter ====================

    public SseEmitter createEmitter(String releaseId) {
        SseEmitter emitter = new SseEmitter(600_000L);
        ReleaseRecord record = releases.get(releaseId);

        if (record == null) {
            emitter.completeWithError(new IllegalArgumentException("Release not found: " + releaseId));
            return emitter;
        }

        List<SseEmitter> list = emitters.computeIfAbsent(releaseId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data(Map.of(
                            "status", record.getStatus().name(),
                            "statusLabel", record.getStatus().getLabel(),
                            "currentStep", record.getCurrentStep(),
                            "logs", record.getLogs(),
                            "finished", record.isFinished(),
                            "duration", record.getDuration())));
        } catch (Exception e) {
            list.remove(emitter);
        }

        if (record.isFinished()) {
            try {
                emitter.send(SseEmitter.event().name("complete").data(Map.of(
                        "status", record.getStatus().name(),
                        "duration", record.getDuration())));
                emitter.complete();
            } catch (Exception e) {
                // ignore
            }
        }

        return emitter;
    }

    private void broadcastLog(ReleaseRecord record) {
        List<SseEmitter> list = emitters.get(record.getId());
        if (list == null || list.isEmpty())
            return;

        List<String> allLogs = record.getLogs();
        if (allLogs.isEmpty())
            return;

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("log")
                        .data(Map.of("logs", allLogs, "full", true)));
            } catch (Exception e) {
                list.remove(emitter);
            }
        }
    }

    private void broadcastStatus(ReleaseRecord record) {
        List<SseEmitter> list = emitters.get(record.getId());
        if (list == null || list.isEmpty())
            return;

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(Map.of(
                                "status", record.getStatus().name(),
                                "statusLabel", record.getStatus().getLabel(),
                                "currentStep", record.getCurrentStep(),
                                "finished", record.isFinished(),
                                "duration", record.getDuration())));
            } catch (Exception e) {
                list.remove(emitter);
            }
        }
    }

    private void completeEmitters(String releaseId) {
        List<SseEmitter> list = emitters.get(releaseId);
        if (list == null)
            return;

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("complete").data("done"));
                emitter.complete();
            } catch (Exception e) {
                // ignore
            }
        }
        list.clear();
    }

    // ==================== 查询方法 ====================

    public ReleaseRecord getReleaseRecord(String id) {
        return releases.get(id);
    }

    public List<ReleaseRecord> listReleaseRecords() {
        List<ReleaseRecord> list = new ArrayList<>(releases.values());
        list.sort((a, b) -> b.getStartTime().compareTo(a.getStartTime()));
        return list;
    }
}
