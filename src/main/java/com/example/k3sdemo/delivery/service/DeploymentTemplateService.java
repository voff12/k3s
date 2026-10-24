package com.example.k3sdemo.delivery.service;

import com.example.k3sdemo.delivery.dto.DeploymentTemplate;
import com.example.k3sdemo.delivery.entity.AppEnvironment;
import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.repository.AppEnvironmentRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/** 应用模板与环境覆盖的合并；只处理配置，不保存或读取 Secret 内容。 */
@Service
public class DeploymentTemplateService {
    private final AppEnvironmentRepository environmentRepository;
    private final ObjectMapper objectMapper;

    public DeploymentTemplateService(AppEnvironmentRepository environmentRepository, ObjectMapper objectMapper) {
        this.environmentRepository = environmentRepository;
        this.objectMapper = objectMapper;
    }

    public DeploymentTemplate resolve(Application application, String environment) {
        DeploymentTemplate merged = read(application.getDeploymentTemplateJson(), "应用部署模板");
        Optional<AppEnvironment> target = environmentRepository.findByAppId(application.getId()).stream()
                .filter(item -> item.getEnv().equals(environment)).findFirst();
        target.ifPresent(item -> merge(merged, read(item.getDeploymentOverrideJson(), "环境部署覆盖")));
        merged.validate();
        return merged;
    }

    public String serialize(DeploymentTemplate template) {
        try {
            if (template == null) return null;
            template.validate();
            return objectMapper.writeValueAsString(template);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("部署模板格式不正确", e);
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public AppEnvironment saveOverride(Long appId, String environment, DeploymentTemplate override) {
        AppEnvironment row = environmentRepository.findByAppId(appId).stream()
                .filter(item -> item.getEnv().equals(environment)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "应用环境不存在: " + environment));
        row.setDeploymentOverrideJson(serialize(override));
        return environmentRepository.save(row);
    }

    public DeploymentTemplate readTemplate(String json) {
        return read(json, "部署模板");
    }

    private DeploymentTemplate read(String json, String source) {
        try {
            return json == null || json.isBlank() ? new DeploymentTemplate() : objectMapper.readValue(json, DeploymentTemplate.class);
        } catch (Exception e) {
            throw new IllegalStateException(source + "无法解析", e);
        }
    }

    public static void merge(DeploymentTemplate base, DeploymentTemplate override) {
        if (override.getEnabled() != null) base.setEnabled(override.getEnabled());
        if (override.getNacos() != null) {
            DeploymentTemplate.NacosConfig baseNacos = base.getNacos();
            DeploymentTemplate.NacosConfig extra = override.getNacos();
            if (baseNacos == null) {
                base.setNacos(extra);
            } else if (extra != null) {
                if (extra.getEnabled() != null) baseNacos.setEnabled(extra.getEnabled());
            if (extra.getServerAddr() != null) baseNacos.setServerAddr(extra.getServerAddr());
            if (extra.getNamespace() != null) baseNacos.setNamespace(extra.getNamespace());
            if (extra.getGroup() != null) baseNacos.setGroup(extra.getGroup());
            if (extra.getDataId() != null) baseNacos.setDataId(extra.getDataId());
            if (extra.getAuthSecretRef() != null) baseNacos.setAuthSecretRef(extra.getAuthSecretRef());
            }
        }
        base.getEnv().putAll(override.getEnv());
        base.getSecretEnv().putAll(override.getSecretEnv());
        base.getEnvFrom().addAll(override.getEnvFrom());
    }
}
