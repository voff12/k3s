package com.example.k3sdemo.delivery.service;

import com.example.k3sdemo.model.PipelineConfig;
import com.example.k3sdemo.model.PipelineRun;
import com.example.k3sdemo.service.DevOpsService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Kaniko 触发器：把交付中心的发布请求转成现有 DevOpsService 流水线。
 * 离线模式与 build.sh 等价（集群内构建 + 导入 containerd + 更新 Deployment）。
 */
@Component
public class KanikoPipelineTrigger implements PipelineTrigger {

    private final DevOpsService devOpsService;

    public KanikoPipelineTrigger(DevOpsService devOpsService) {
        this.devOpsService = devOpsService;
    }

    @Override
    public String trigger(String gitUrl, String branch, String imageName,
                          String deployment, String namespace) {
        PipelineConfig config = new PipelineConfig();
        config.setGitUrl(gitUrl);
        config.setBranch(branch != null && !branch.isBlank() ? branch : "main");
        config.setImageName(imageName);
        config.setImageTag("rel-" + System.currentTimeMillis());
        config.setDeploymentName(deployment);
        config.setNamespace(namespace != null && !namespace.isBlank() ? namespace : "default");
        config.setRuntime("auto");
        try {
            PipelineRun run = devOpsService.triggerPipeline(config);
            return run.getId();
        } catch (Exception e) {
            throw new TriggerException("触发流水线失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> poll(String pipelineId) {
        PipelineRun run = devOpsService.getPipelineRun(pipelineId);
        if (run == null) {
            throw new TriggerException("流水线不存在: " + pipelineId);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("status", run.getStatus().name());
        m.put("finished", run.isFinished());
        m.put("imageRef", run.getConfig().getFullImageRef(null, null));
        m.put("error", run.getErrorMessage());
        return m;
    }
}
