package com.example.k3sdemo.mcp;

import com.example.k3sdemo.delivery.dto.CreateApplicationRequest;
import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.entity.Artifact;
import com.example.k3sdemo.delivery.entity.Release;
import com.example.k3sdemo.delivery.entity.ReleaseStage;
import com.example.k3sdemo.delivery.repository.ArtifactRepository;
import com.example.k3sdemo.delivery.service.ApplicationService;
import com.example.k3sdemo.delivery.service.ReleaseExecutionService;
import com.example.k3sdemo.delivery.service.ReleasePersistenceService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交付中心发布的 MCP 工具（7 个，结果导向设计）。
 * 长任务模式：release_create → release_execute 返回后立即拿 releaseId，LLM 轮询 release_status。
 * 所有业务异常（ResponseStatusException）转为 {success:false,error} 让模型自我纠正。
 */
@Component
public class DeliveryMcpTools {

    private final ApplicationService applicationService;
    private final ReleasePersistenceService persistenceService;
    private final ReleaseExecutionService executionService;
    private final ArtifactRepository artifactRepository;

    public DeliveryMcpTools(ApplicationService applicationService,
                            ReleasePersistenceService persistenceService,
                            ReleaseExecutionService executionService,
                            ArtifactRepository artifactRepository) {
        this.applicationService = applicationService;
        this.persistenceService = persistenceService;
        this.executionService = executionService;
        this.artifactRepository = artifactRepository;
    }

    @McpTool(name = "delivery_app_list", description = "列出交付中心已接入的应用（不含 gitToken 明文）。发布前先调用它找到目标应用的 id。")
    public Map<String, Object> appList(
            @McpToolParam(description = "按团队过滤，可选", required = false) String team) {
        try {
            List<Application> apps = applicationService.list(team);
            List<Map<String, Object>> items = apps.stream().map(this::appView).toList();
            return ok(Map.of("count", items.size(), "applications", items));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @McpTool(name = "delivery_app_create", description = "接入新应用（连接 Git 仓库）。code 需全局唯一英文标识，如 order-service。")
    public Map<String, Object> appCreate(
            @McpToolParam(description = "应用显示名") String name,
            @McpToolParam(description = "英文标识，全局唯一，如 order-service") String code,
            @McpToolParam(description = "Git 仓库地址") String repoUrl,
            @McpToolParam(description = "私有仓库访问 token，可选，登记制品时读取最新提交用", required = false) String gitToken,
            @McpToolParam(description = "默认分支，可选，缺省 main", required = false) String defaultBranch,
            @McpToolParam(description = "运行时类型 java/python/docker，可选，接入时自动识别", required = false) String runtimeType,
            @McpToolParam(description = "部署模板，可选；只含普通 env、Secret 引用和 Nacos 连接，不含 Secret 明文", required = false) com.example.k3sdemo.delivery.dto.DeploymentTemplate deploymentTemplate) {
        try {
            CreateApplicationRequest req = new CreateApplicationRequest();
            req.setName(name);
            req.setCode(code);
            req.setRepoUrl(repoUrl);
            req.setGitToken(gitToken);
            req.setDefaultBranch(defaultBranch);
            req.setRuntimeType(runtimeType);
            req.setDeploymentTemplate(deploymentTemplate);
            Application app = applicationService.create(req);
            return ok(Map.of("application", appView(app)));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @McpTool(name = "delivery_artifact_list", description = "查询某应用下的构建制品列表（按创建时间倒序）。创建发布前先选制品。")
    public Map<String, Object> artifactList(
            @McpToolParam(description = "应用 id") Long appId) {
        try {
            List<Artifact> artifacts = artifactRepository.findByAppIdOrderByCreatedAtDesc(appId);
            List<Map<String, Object>> items = artifacts.stream().map(this::artifactView).toList();
            return ok(Map.of("count", items.size(), "artifacts", items));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @McpTool(name = "delivery_release_create", description = "为某应用的指定制品创建发布单（PENDING 状态），返回 releaseId。之后调用 delivery_release_execute 触发执行。")
    public Map<String, Object> releaseCreate(
            @McpToolParam(description = "应用 id") Long applicationId,
            @McpToolParam(description = "制品 id") Long artifactId,
            @McpToolParam(description = "目标环境: PREVIEW / BETA / PROD") String targetEnv,
            @McpToolParam(description = "发布策略: CANARY / BLUE_GREEN / DEPLOY_ONLY") String strategy,
            @McpToolParam(description = "发布备注，可选", required = false) String note) {
        try {
            Release release = persistenceService.create(applicationId, artifactId, targetEnv, strategy, note, "mcp");
            return ok(Map.of("releaseId", release.getId(), "releaseNo", release.getReleaseNo(),
                    "status", release.getStatus()));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @McpTool(name = "delivery_release_execute", description = "触发执行一个 PENDING 或 FAILED 状态的发布单：启动构建→推送→部署流水线，立即返回。之后用 delivery_release_status 轮询进度。")
    public Map<String, Object> releaseExecute(
            @McpToolParam(description = "发布单 id") Long releaseId) {
        try {
            Release release = executionService.execute(releaseId);
            return ok(Map.of("releaseId", release.getId(), "status", release.getStatus(),
                    "message", "流水线已启动，请用 delivery_release_status 轮询"));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @McpTool(name = "delivery_release_status", description = "查询发布单状态与 6 阶段进度（PR_CHECK/BUILD/PREVIEW/BETA/CANARY/STABLE）。用于执行后轮询。")
    public Map<String, Object> releaseStatus(
            @McpToolParam(description = "发布单 id") Long releaseId) {
        try {
            Release release = persistenceService.get(releaseId);
            List<ReleaseStage> stages = persistenceService.stages(releaseId);
            List<Map<String, Object>> stageViews = stages.stream()
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<String, Object>();
                        m.put("stage", s.getStageCode());
                        m.put("name", s.getStageName());
                        m.put("status", s.getStatus());
                        return m;
                    })
                    .toList();
            return ok(Map.of("releaseId", release.getId(), "releaseNo", release.getReleaseNo(),
                    "status", release.getStatus(), "currentStage",
                    release.getCurrentStage() == null ? "" : release.getCurrentStage(),
                    "stages", stageViews));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @McpTool(name = "delivery_release_list", description = "列出发布单，可按状态过滤（PENDING/RUNNING/SUCCESS/FAILED 等）。")
    public Map<String, Object> releaseList(
            @McpToolParam(description = "状态过滤，可选", required = false) String status) {
        try {
            List<Release> releases = persistenceService.list(status);
            List<Map<String, Object>> items = releases.stream()
                    .map(r -> {
                        Map<String, Object> m = new LinkedHashMap<String, Object>();
                        m.put("releaseId", r.getId());
                        m.put("releaseNo", r.getReleaseNo());
                        m.put("appId", r.getAppId());
                        m.put("env", r.getEnv());
                        m.put("status", r.getStatus());
                        return m;
                    })
                    .toList();
            return ok(Map.of("count", items.size(), "releases", items));
        } catch (Exception e) {
            return fail(e);
        }
    }

    private Map<String, Object> appView(Application a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("name", a.getName());
        m.put("code", a.getCode());
        m.put("repoUrl", a.getRepoUrl());
        m.put("defaultBranch", a.getDefaultBranch());
        m.put("status", a.getStatus());
        if (a.getDeploymentTemplateJson() != null) {
            m.put("deploymentTemplate", applicationService.deploymentTemplate(a.getId()));
        }
        return m;
    }

    private Map<String, Object> artifactView(Artifact a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("appId", a.getAppId());
        m.put("version", a.getVersion());
        m.put("gitBranch", a.getGitBranch());
        m.put("imageRepo", a.getImageRepo());
        m.put("buildStatus", a.getBuildStatus());
        return m;
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.putAll(data);
        return m;
    }

    private Map<String, Object> fail(Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        String msg = (e instanceof ResponseStatusException rse && rse.getReason() != null)
                ? rse.getReason() : e.getMessage();
        m.put("error", msg);
        return m;
    }
}
