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
