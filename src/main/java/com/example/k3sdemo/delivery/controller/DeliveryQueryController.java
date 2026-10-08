package com.example.k3sdemo.delivery.controller;

import com.example.k3sdemo.delivery.dto.ApiResponse;
import com.example.k3sdemo.delivery.entity.ActivityLog;
import com.example.k3sdemo.delivery.entity.AppEnvironment;
import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.entity.Artifact;
import com.example.k3sdemo.delivery.entity.Integration;
import com.example.k3sdemo.delivery.entity.PolicyTemplate;
import com.example.k3sdemo.delivery.repository.AppEnvironmentRepository;
import com.example.k3sdemo.delivery.repository.ArtifactRepository;
import com.example.k3sdemo.delivery.repository.IntegrationRepository;
import com.example.k3sdemo.delivery.repository.PolicyTemplateRepository;
import com.example.k3sdemo.delivery.service.ActivityLogService;
import com.example.k3sdemo.delivery.service.ApplicationService;
import com.example.k3sdemo.delivery.service.MetricsQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交付中心只读查询接口：活动流、制品列表、平台设置（设计 4.4 / 4.5 / 4.9）。
 */
@RestController
@RequestMapping("/api/delivery")
public class DeliveryQueryController {

    private final ActivityLogService activityLogService;
    private final ApplicationService applicationService;
    private final AppEnvironmentRepository appEnvironmentRepository;
    private final ArtifactRepository artifactRepository;
    private final IntegrationRepository integrationRepository;
    private final PolicyTemplateRepository policyTemplateRepository;
    private final MetricsQueryService metricsQueryService;

    public DeliveryQueryController(ActivityLogService activityLogService,
                                   ApplicationService applicationService,
                                   AppEnvironmentRepository appEnvironmentRepository,
                                   ArtifactRepository artifactRepository,
                                   IntegrationRepository integrationRepository,
                                   PolicyTemplateRepository policyTemplateRepository,
                                   MetricsQueryService metricsQueryService) {
        this.activityLogService = activityLogService;
        this.applicationService = applicationService;
        this.appEnvironmentRepository = appEnvironmentRepository;
        this.artifactRepository = artifactRepository;
        this.integrationRepository = integrationRepository;
        this.policyTemplateRepository = policyTemplateRepository;
        this.metricsQueryService = metricsQueryService;
    }

    /** 最近活动流（默认 30 条，上限 100）。 */
    @GetMapping("/activities")
    public ApiResponse<List<ActivityLog>> activities(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(activityLogService.recent(limit));
    }

    /** 运行质量指标（Prometheus 未配置/不可达时 available=false，前端显示空态）。 */
    @GetMapping("/quality")
    public ApiResponse<Map<String, Object>> quality() {
        return ApiResponse.ok(metricsQueryService.qualityOverview());
    }

    /** 服务成功率总览 + 趋势（设计 4.4 P4）。window 支持 1h/6h/24h/7d，默认 24h。 */
    @GetMapping("/overview/success-rate")
    public ApiResponse<Map<String, Object>> successRate(@RequestParam(defaultValue = "24h") String window) {
        return ApiResponse.ok(metricsQueryService.successRateOverview(window));
    }

    /** 应用目录（带环境列表，目录页主数据源）。 */
    @GetMapping("/app-catalog")
    public ApiResponse<List<Map<String, Object>>> appCatalog() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Application app : applicationService.list(null)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", app.getId());
            m.put("name", app.getName());
            m.put("code", app.getCode());
            m.put("team", app.getTeam());
            m.put("repoUrl", app.getRepoUrl());
            m.put("runtimeType", app.getRuntimeType());
            m.put("port", app.getPort());
            m.put("prodEnabled", app.getProdEnabled());
            m.put("updatedAt", app.getUpdatedAt());
            m.put("environments", appEnvironmentRepository.findByAppId(app.getId())
                    .stream().map(AppEnvironment::getEnv).toList());
            result.add(m);
        }
        return ApiResponse.ok(result);
    }

    /** 某应用的制品列表（新建发布向导第 1 步）。appId 必填。 */
    @GetMapping("/artifacts")
    public ApiResponse<List<Artifact>> artifacts(@RequestParam Long appId) {
        if (appId == null || appId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "appId 不能为空");
        }
        return ApiResponse.ok(artifactRepository.findByAppIdOrderByCreatedAtDesc(appId));
    }

    /**
     * 制品登记（供流水线/页面表单注册构建产物）。
     * digest 可不传：由 repo:version@sha 确定性生成（离线模式合成值，同一输入幂等）。
     * digest 唯一，重复登记同一 digest 返回 409。
     */
    @PostMapping("/artifacts")
    public ApiResponse<Artifact> registerArtifact(@RequestBody Artifact artifact) {
        if (artifact.getAppId() == null || artifact.getVersion() == null
                || artifact.getGitSha() == null || artifact.getImageRepo() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "appId、version、gitSha、imageRepo 不能为空");
        }
        if (artifact.getImageDigest() == null || artifact.getImageDigest().isBlank()) {
            artifact.setImageDigest("sha256:" + sha256Hex(
                    artifact.getImageRepo() + ":" + artifact.getVersion()
                            + "@" + artifact.getGitSha()));
        }
        applicationService.get(artifact.getAppId()); // 404 若应用不存在
        try {
            return ApiResponse.ok(artifactRepository.save(artifact));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "制品 digest 已存在");
        }
    }

    private String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 平台设置：集成连接 + 发布策略模板。 */
    @GetMapping("/settings")
    public ApiResponse<Map<String, Object>> settings() {
        List<Integration> integrations = integrationRepository.findAll();
        List<PolicyTemplate> templates = policyTemplateRepository.findAll();
        return ApiResponse.ok(Map.of(
                "integrations", integrations,
                "policyTemplates", templates
        ));
    }
}
