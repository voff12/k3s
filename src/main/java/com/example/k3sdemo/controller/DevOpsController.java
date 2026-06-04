package com.example.k3sdemo.controller;

import com.example.k3sdemo.model.PipelineConfig;
import com.example.k3sdemo.model.PipelineRun;
import com.example.k3sdemo.service.DevOpsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * DevOps Pipeline Controller — page rendering, REST API, and SSE streaming.
 */
@Controller
public class DevOpsController {

    @Autowired
    private DevOpsService devOpsService;

    /**
     * Render the DevOps pipeline dashboard page.
     */
    @GetMapping("/devops")
    public String index(Model model) {
        try {
            List<PipelineRun> runs = devOpsService.listPipelineRuns();
            model.addAttribute("pipelineRuns", runs != null ? runs : Collections.emptyList());
        } catch (Exception e) {
            model.addAttribute("pipelineRuns", Collections.emptyList());
        }
        return "devops";
    }

    /**
     * Trigger a new pipeline run.
     */
    @PostMapping("/devops/pipeline/run")
    @ResponseBody
    public Map<String, Object> runPipeline(@RequestBody PipelineConfig config) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (config.getGitUrl() == null || config.getGitUrl().isEmpty()) {
                result.put("success", false);
                result.put("error", "Git仓库地址不能为空");
                return result;
            }
            if (config.getImageName() == null || config.getImageName().isEmpty()) {
                result.put("success", false);
                result.put("error", "镜像名称不能为空");
                return result;
            }
            String rtErr = config.validateRuntimeFields();
            if (rtErr != null) {
                result.put("success", false);
                result.put("error", rtErr);
                return result;
            }

            PipelineRun run = devOpsService.triggerPipeline(config);
            result.put("success", true);
            result.put("id", run.getId());
            result.put("status", run.getStatus().name());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "触发流水线失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 触发多分支合并预览部署。
     * 接收 base + featureBranches, 合并后部署到独立预览命名空间 preview-&lt;mergeSetId&gt;。
     */
    @PostMapping("/devops/merge-deploy/run")
    @ResponseBody
    public Map<String, Object> runMergeDeploy(@RequestBody PipelineConfig config) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (config.getGitUrl() == null || config.getGitUrl().isEmpty()) {
                result.put("success", false);
                result.put("error", "Git仓库地址不能为空");
                return result;
            }
            if (config.getImageName() == null || config.getImageName().isEmpty()) {
                result.put("success", false);
                result.put("error", "镜像名称不能为空");
                return result;
            }
            if (config.getNormalizedFeatureBranches().isEmpty()) {
                result.put("success", false);
                result.put("error", "合并部署至少需要选择一个待合并分支 (feature branch)");
                return result;
            }
            // 基础分支名校验: 拒绝可能导致 shell 注入的字符
            for (String b : config.getNormalizedFeatureBranches()) {
                if (!isSafeBranchName(b)) {
                    result.put("success", false);
                    result.put("error", "非法分支名: " + b);
                    return result;
                }
            }
            if (!isSafeBranchName(config.getEffectiveBaseBranch())) {
                result.put("success", false);
                result.put("error", "非法基底分支名: " + config.getEffectiveBaseBranch());
                return result;
            }
            String rtErr = config.validateRuntimeFields();
            if (rtErr != null) {
                result.put("success", false);
                result.put("error", rtErr);
                return result;
            }

            PipelineRun run = devOpsService.triggerPipeline(config);
            result.put("success", true);
            result.put("id", run.getId());
            result.put("status", run.getStatus().name());
            result.put("previewNamespace", config.getPreviewNamespace());
            result.put("mergeSetId", config.getMergeSetId());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "触发合并部署失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 校验分支名是否安全 (仅允许字母数字及 / _ - . )。
     */
    private boolean isSafeBranchName(String name) {
        return name != null && name.matches("[A-Za-z0-9._/\\-]+");
    }

    /**
     * 列出远程仓库的分支, 供前端多选下拉填充。
     */
    @GetMapping("/devops/branches")
    @ResponseBody
    public Map<String, Object> listBranches(@RequestParam String gitUrl,
            @RequestParam(required = false) String token) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<String> branches = devOpsService.listRemoteBranches(gitUrl, token);
            result.put("success", true);
            result.put("branches", branches);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("branches", Collections.emptyList());
        }
        return result;
    }

    /**
     * 列出当前所有合并预览环境。
     */
    @GetMapping("/devops/previews")
    @ResponseBody
    public List<Map<String, Object>> listPreviews() {
        return devOpsService.listPreviewEnvironments();
    }

    /**
     * 销毁指定预览环境 (删除 preview-&lt;id&gt; 命名空间)。
     */
    @DeleteMapping("/devops/previews/{id}")
    @ResponseBody
    public Map<String, Object> destroyPreview(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        boolean ok = devOpsService.destroyPreviewEnvironment(id);
        result.put("success", ok);
        if (!ok) {
            result.put("error", "未找到可删除的受管预览环境: " + id);
        }
        return result;
    }

    /**
     * SSE stream for real-time pipeline logs and status.
     */
    @GetMapping(value = "/devops/pipeline/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPipeline(@PathVariable String id) {
        return devOpsService.createEmitter(id);
    }

    /**
     * Get pipeline run status snapshot.
     */
    @GetMapping("/devops/pipeline/{id}/status")
    @ResponseBody
    public Map<String, Object> getPipelineStatus(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        PipelineRun run = devOpsService.getPipelineRun(id);
        if (run == null) {
            result.put("success", false);
            result.put("error", "流水线不存在: " + id);
            return result;
        }
        result.put("success", true);
        result.put("id", run.getId());
        result.put("status", run.getStatus().name());
        result.put("statusLabel", run.getStatus().getLabel());
        result.put("currentStep", run.getCurrentStep());
        result.put("finished", run.isFinished());
        result.put("duration", run.getDuration());
        result.put("startTime", run.getStartTimeFormatted());
        result.put("imageName", run.getConfig().getImageName());
        result.put("gitUrl", run.getConfig().getGitUrl());
        result.put("branch", run.getConfig().getBranch());
        // 合并预览部署相关
        result.put("mergeDeploy", run.isMergeDeploy());
        if (run.isMergeDeploy()) {
            result.put("baseBranch", run.getConfig().getEffectiveBaseBranch());
            result.put("featureBranches", run.getConfig().getNormalizedFeatureBranches());
            result.put("mergeCommitSha", run.getMergeCommitSha());
            result.put("conflictFiles", run.getConflictFiles());
            result.put("previewNamespace", run.getPreviewNamespace());
            result.put("previewNodePortUrl", run.getPreviewNodePortUrl());
        }
        return result;
    }

    /**
     * List all pipeline runs as JSON.
     */
    @GetMapping("/devops/pipelines")
    @ResponseBody
    public List<Map<String, Object>> listPipelines() {
        return devOpsService.listPipelineRuns().stream()
                .map(run -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", run.getId());
                    m.put("status", run.getStatus().name());
                    m.put("statusLabel", run.getStatus().getLabel());
                    m.put("currentStep", run.getCurrentStep());
                    m.put("finished", run.isFinished());
                    m.put("duration", run.getDuration());
                    m.put("startTime", run.getStartTimeFormatted());
                    m.put("imageName", run.getConfig().getImageName());
                    m.put("gitUrl", run.getConfig().getGitUrl());
                    m.put("branch", run.getConfig().getBranch());
                    m.put("mergeDeploy", run.isMergeDeploy());
                    if (run.isMergeDeploy()) {
                        m.put("baseBranch", run.getConfig().getEffectiveBaseBranch());
                        m.put("featureBranches", run.getConfig().getNormalizedFeatureBranches());
                        m.put("previewNamespace", run.getPreviewNamespace());
                        m.put("previewNodePortUrl", run.getPreviewNodePortUrl());
                        m.put("conflictFiles", run.getConflictFiles());
                    }
                    return m;
                })
                .collect(Collectors.toList());
    }
}
