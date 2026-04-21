package com.example.k3sdemo.controller;

import com.example.k3sdemo.service.HaAgentService;
import com.example.k3sdemo.service.HaAgentService.ClusterHealthReport;
import com.example.k3sdemo.service.QwenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

@Controller
public class HaAgentController {

    @Autowired
    private HaAgentService haAgentService;

    @Autowired
    private QwenService qwenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/haagent")
    public String index() {
        return "haagent";
    }

    /**
     * Scans the cluster for HA issues and returns a structured summary as JSON.
     * Used by the frontend to display the raw scan results before AI analysis.
     */
    @GetMapping("/haagent/summary")
    @ResponseBody
    public Map<String, Object> summary() {
        Map<String, Object> result = new HashMap<>();
        try {
            ClusterHealthReport report = haAgentService.scan();
            result.put("success", true);
            result.put("totalNodes", report.totalNodes);
            result.put("readyNodes", report.readyNodes);
            result.put("totalPods", report.totalPods);
            result.put("runningPods", report.runningPods);
            result.put("totalDeployments", report.totalDeployments);
            result.put("totalIngresses", report.totalIngresses);
            result.put("podIssueCount", report.podIssues.size());
            result.put("deploymentIssueCount", report.deploymentIssues.size());
            result.put("nodeIssueCount",
                    report.nodes.stream().filter(n -> !n.ready || n.memoryPressure || n.diskPressure || n.pidPressure)
                            .count());
            result.put("ingressControllerCount", report.ingressControllers.size());

            // Serialize issue details for display
            result.put("podIssues", report.podIssues);
            result.put("deploymentIssues", report.deploymentIssues);
            result.put("nodeIssues", report.nodes.stream()
                    .filter(n -> !n.ready || n.memoryPressure || n.diskPressure || n.pidPressure)
                    .collect(java.util.stream.Collectors.toList()));
            result.put("ingressControllers", report.ingressControllers);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "扫描失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * Scans the cluster, builds an AI prompt from the results, and streams
     * the Qwen analysis back to the client via Server-Sent Events.
     */
    @PostMapping("/haagent/analyze")
    public SseEmitter analyze() {
        SseEmitter emitter = new SseEmitter(300000L); // 5 min timeout

        emitter.onTimeout(() -> {
            try { emitter.complete(); } catch (Exception ignored) {}
        });
        emitter.onError(ex -> {
            try { emitter.complete(); } catch (Exception ignored) {}
        });

        new Thread(() -> {
            try {
                // Step 1: scan cluster
                ClusterHealthReport report = haAgentService.scan();
                // Step 2: build prompt
                String prompt = haAgentService.buildPrompt(report);
                // Step 3: stream AI response
                qwenService.streamChat(prompt, emitter);
            } catch (Exception e) {
                try {
                    emitter.send("Error: " + e.getMessage());
                    emitter.completeWithError(e);
                } catch (Exception ignored) {}
            }
        }).start();

        return emitter;
    }
}
