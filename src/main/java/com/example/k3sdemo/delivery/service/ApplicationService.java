package com.example.k3sdemo.delivery.service;

import com.example.k3sdemo.delivery.dto.CreateApplicationRequest;
import com.example.k3sdemo.delivery.entity.AppEnvironment;
import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.entity.PolicyTemplate;
import com.example.k3sdemo.delivery.repository.AppEnvironmentRepository;
import com.example.k3sdemo.delivery.repository.ApplicationRepository;
import com.example.k3sdemo.delivery.repository.PolicyTemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用接入与目录（P1）。
 */
@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final AppEnvironmentRepository appEnvironmentRepository;
    private final PolicyTemplateRepository policyTemplateRepository;
    private final ActivityLogService activityLog;

    public ApplicationService(ApplicationRepository applicationRepository,
                              AppEnvironmentRepository appEnvironmentRepository,
                              PolicyTemplateRepository policyTemplateRepository,
                              ActivityLogService activityLog) {
        this.applicationRepository = applicationRepository;
        this.appEnvironmentRepository = appEnvironmentRepository;
        this.policyTemplateRepository = policyTemplateRepository;
        this.activityLog = activityLog;
    }

    public List<Application> list(String team) {
        String status = "ACTIVE";
        if (team == null || team.isBlank()) {
            return applicationRepository.findByStatusOrderByUpdatedAtDesc(status);
        }
        return applicationRepository.findByStatusAndTeamOrderByUpdatedAtDesc(status, team);
    }

    public Application get(Long id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "应用不存在: " + id));
    }

    @Transactional
    public Application create(CreateApplicationRequest req) {
        validate(req);
        String code = resolveCode(req);
        List<String> environments = normalizeEnvironments(req.getEnvironments());
        if (applicationRepository.findByCode(code).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "应用标识已存在: " + code);
        }

        Application app = new Application();
        app.setName(req.getName().trim());
        app.setCode(code);
        app.setTeam(req.getTeam());
        app.setRepoUrl(req.getRepoUrl().trim());
        app.setRepoProvider(normalizeProvider(req.getRepoProvider()));
        app.setDefaultBranch(req.getDefaultBranch() != null && !req.getDefaultBranch().isBlank()
                ? req.getDefaultBranch().trim() : "main");
        app.setRuntimeType(req.getRuntimeType());
        app.setPort(req.getPort());
        app.setNamespace(req.getNamespace());
        app.setHealthPath(req.getHealthPath());
        app.setPolicyTemplateId(resolvePolicyTemplateId(req.getPolicyTemplateCode()));
        app.setProdEnabled(environments.contains("PROD"));
        Application saved = applicationRepository.save(app);

        saveEnvironments(saved, environments);
        activityLog.log(saved.getId(), null, "USER", req.getOperatorName(), "APP_ONBOARDED",
                "接入应用 " + saved.getName(), null);
        return saved;
    }

    @Transactional
    public Application disable(Long id) {
        Application app = get(id);
        app.setStatus("DISABLED");
        return applicationRepository.save(app);
    }

    private void validate(CreateApplicationRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "应用名称不能为空");
        }
        if (req.getRepoUrl() == null || req.getRepoUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "代码仓库地址不能为空");
        }
    }

    /** 优先用请求里的标识，否则从仓库地址推导；空或超出列宽（64）都拒绝 */
    private String resolveCode(CreateApplicationRequest req) {
        String code = req.getCode() != null && !req.getCode().isBlank()
                ? req.getCode().trim()
                : deriveCode(req.getRepoUrl());
        if (code.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法从仓库地址推导应用标识，请手动填写");
        }
        if (code.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "应用标识不能超过 64 个字符: " + code);
        }
        return code;
    }

    /** 从仓库地址推导应用标识：https://git.example.com/team/order-service.git → order-service */
    private String deriveCode(String repoUrl) {
        String s = repoUrl.trim().replaceAll("/+$", "");
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        return s.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "gitlab";
        }
        String p = provider.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "gitlab", "github" -> p;
            default -> "other";
        };
    }

    private Long resolvePolicyTemplateId(String policyCode) {
        if (policyCode == null || policyCode.isBlank()) {
            return null;
        }
        return policyTemplateRepository.findByCode(policyCode.trim())
                .map(PolicyTemplate::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "策略模板不存在: " + policyCode));
    }

    /** trim + 转大写 + 去重；空项或未知环境返回 400 */
    private List<String> normalizeEnvironments(List<String> environments) {
        if (environments == null) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String env : environments) {
            if (env == null || env.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "环境不能为空");
            }
            String e = env.trim().toUpperCase(Locale.ROOT);
            if (!List.of("PREVIEW", "BETA", "PROD").contains(e)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知环境: " + env);
            }
            normalized.add(e);
        }
        return List.copyOf(normalized);
    }

    private void saveEnvironments(Application app, List<String> environments) {
        for (String env : environments) {
            AppEnvironment row = new AppEnvironment();
            row.setAppId(app.getId());
            row.setEnv(env);
            row.setEnabled(true);
            appEnvironmentRepository.save(row);
        }
    }
}
