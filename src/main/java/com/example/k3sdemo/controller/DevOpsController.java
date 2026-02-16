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
                    return m;
                })
                .collect(Collectors.toList());
    }
}
