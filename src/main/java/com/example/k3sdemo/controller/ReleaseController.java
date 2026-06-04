package com.example.k3sdemo.controller;

import com.example.k3sdemo.model.ReleaseConfig;
import com.example.k3sdemo.model.ReleaseRecord;
import com.example.k3sdemo.service.ReleaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 应用发布管理 Controller — 页面渲染、REST API、SSE 流式日志。
 */
@Controller
public class ReleaseController {

    @Autowired
    private ReleaseService releaseService;

    /**
     * 渲染发布管理页面。
     */
    @GetMapping("/release")
    public String index(Model model) {
        try {
            List<ReleaseRecord> records = releaseService.listReleaseRecords();
            model.addAttribute("releases", records != null ? records : Collections.emptyList());
        } catch (Exception e) {
            model.addAttribute("releases", Collections.emptyList());
        }
        return "release";
    }

    /**
     * 触发新发布。
     */
    @PostMapping("/release/run")
    @ResponseBody
    public Map<String, Object> runRelease(@RequestBody ReleaseConfig config) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (config.getGitUrl() == null || config.getGitUrl().isEmpty()) {
                result.put("success", false);
                result.put("error", "Git 仓库地址不能为空");
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

            ReleaseRecord record = releaseService.triggerRelease(config);
            result.put("success", true);
            result.put("id", record.getId());
            result.put("status", record.getStatus().name());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "触发发布失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 触发多分支合并预览发布 (Harbor 模式)。
     */
    @PostMapping("/release/merge-deploy/run")
    @ResponseBody
    public Map<String, Object> runMergeRelease(@RequestBody ReleaseConfig config) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (config.getGitUrl() == null || config.getGitUrl().isEmpty()) {
                result.put("success", false);
                result.put("error", "Git 仓库地址不能为空");
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

            ReleaseRecord record = releaseService.triggerRelease(config);
            result.put("success", true);
            result.put("id", record.getId());
            result.put("status", record.getStatus().name());
            result.put("previewNamespace", config.getPreviewNamespace());
            result.put("mergeSetId", config.getMergeSetId());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "触发合并发布失败: " + e.getMessage());
        }
        return result;
    }

    private boolean isSafeBranchName(String name) {
        return name != null && name.matches("[A-Za-z0-9._/\\-]+");
    }

    /**
     * SSE 实时日志流。
     */
    @GetMapping(value = "/release/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRelease(@PathVariable String id) {
        return releaseService.createEmitter(id);
    }

    /**
     * 查询发布状态快照。
     */
    @GetMapping("/release/{id}/status")
    @ResponseBody
    public Map<String, Object> getReleaseStatus(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        ReleaseRecord record = releaseService.getReleaseRecord(id);
        if (record == null) {
            result.put("success", false);
            result.put("error", "发布记录不存在: " + id);
            return result;
        }
        result.put("success", true);
        result.put("id", record.getId());
        result.put("status", record.getStatus().name());
        result.put("statusLabel", record.getStatus().getLabel());
        result.put("currentStep", record.getCurrentStep());
        result.put("finished", record.isFinished());
        result.put("duration", record.getDuration());
        result.put("startTime", record.getStartTimeFormatted());
        result.put("imageName", record.getConfig().getImageName());
        result.put("gitUrl", record.getConfig().getGitUrl());
        result.put("branch", record.getConfig().getBranch());
        result.put("runtime", record.getConfig().resolveRuntime());
        result.put("pythonServer", record.getConfig().getPythonServer());
        result.put("mergeDeploy", record.isMergeDeploy());
        if (record.isMergeDeploy()) {
            result.put("baseBranch", record.getConfig().getEffectiveBaseBranch());
            result.put("featureBranches", record.getConfig().getNormalizedFeatureBranches());
            result.put("mergeCommitSha", record.getMergeCommitSha());
            result.put("conflictFiles", record.getConflictFiles());
            result.put("previewNamespace", record.getPreviewNamespace());
            result.put("previewNodePortUrl", record.getPreviewNodePortUrl());
        }
        return result;
    }

    /**
     * 获取发布记录列表 JSON。
     */
    @GetMapping("/release/list")
    @ResponseBody
    public List<Map<String, Object>> listReleases() {
        return releaseService.listReleaseRecords().stream()
                .map(record -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", record.getId());
                    m.put("status", record.getStatus().name());
                    m.put("statusLabel", record.getStatus().getLabel());
                    m.put("currentStep", record.getCurrentStep());
                    m.put("finished", record.isFinished());
                    m.put("duration", record.getDuration());
                    m.put("startTime", record.getStartTimeFormatted());
                    m.put("imageName", record.getConfig().getImageName());
                    m.put("gitUrl", record.getConfig().getGitUrl());
                    m.put("branch", record.getConfig().getBranch());
                    m.put("harborProject", record.getConfig().getHarborProject());
                    return m;
                })
                .collect(Collectors.toList());
    }
}
