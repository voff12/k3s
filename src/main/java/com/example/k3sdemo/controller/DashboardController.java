package com.example.k3sdemo.controller;

import com.example.k3sdemo.service.QwenService;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class DashboardController {

    @Value("${k8s.master.url:}")
    private String masterUrl;

    @Value("${k8s.kubeconfig:}")
    private String kubeconfig;

    @Autowired
    private QwenService qwenService;

    @GetMapping("/dashboard")
    public String index(Model model) {
        initClient();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            // 1. Nodes
            List<Node> nodes = client.nodes().list().getItems();
            long totalNodes = nodes.size();
            long readyNodes = nodes.stream().filter(n -> n.getStatus().getConditions().stream()
                    .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()))).count();

            // 2. Pods
            List<Pod> pods = client.pods().inAnyNamespace().list().getItems();
            long totalPods = pods.size();
            Map<String, Long> podsByStatus = pods.stream()
                    .collect(Collectors.groupingBy(p -> p.getStatus().getPhase(), Collectors.counting()));

            // 3. Deployments
            List<Deployment> deployments = client.apps().deployments().inAnyNamespace().list().getItems();
            long totalDeployments = deployments.size();

            // 4. Events (Recent)
            List<Event> events = client.v1().events().inAnyNamespace().list().getItems();
            // Sort by last timestamp desc and take top 10
            List<Event> recentEvents = events.stream()
                    .sorted((e1, e2) -> {
                        String t1 = e1.getLastTimestamp();
                        if (t1 == null && e1.getEventTime() != null) {
                            t1 = e1.getEventTime().getTime();
                        }
                        String t2 = e2.getLastTimestamp();
                        if (t2 == null && e2.getEventTime() != null) {
                            t2 = e2.getEventTime().getTime();
                        }

                        if (t1 == null && t2 == null)
                            return 0;
                        if (t1 == null)
                            return 1;
                        if (t2 == null)
                            return -1;
                        return t2.compareTo(t1);
                    })
                    .limit(10)
                    .collect(Collectors.toList());

            model.addAttribute("totalNodes", totalNodes);
            model.addAttribute("readyNodes", readyNodes);
            model.addAttribute("totalPods", totalPods);
            model.addAttribute("runningPods", podsByStatus.getOrDefault("Running", 0L));
            model.addAttribute("failedPods", podsByStatus.getOrDefault("Failed", 0L));
            model.addAttribute("pendingPods", podsByStatus.getOrDefault("Pending", 0L));
            model.addAttribute("totalDeployments", totalDeployments);
            model.addAttribute("events", recentEvents);

        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("error", "Failed to fetch dashboard data: " + e.getMessage());
        }
        return "dashboard";
    }

    @PostMapping("/dashboard/analyze-event")
    @ResponseBody
    public Map<String, Object> analyzeEvent(@RequestBody Map<String, String> payload) {
        Map<String, Object> result = new HashMap<>();
        try {
            String message = payload.get("message");
            String type = payload.get("type");
            String reason = payload.get("reason");
            String object = payload.get("object");
            
            if (message == null || message.isEmpty()) {
                result.put("success", false);
                result.put("error", "事件消息为空");
                return result;
            }
            
            // 构建AI分析的prompt
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("请分析以下 Kubernetes 集群事件，并提供详细的原因分析和解决建议。\n\n");
            promptBuilder.append("## 事件信息：\n");
            promptBuilder.append("- 类型: ").append(type != null ? type : "Normal").append("\n");
            promptBuilder.append("- 原因: ").append(reason != null ? reason : "未知").append("\n");
            promptBuilder.append("- 对象: ").append(object != null ? object : "未知").append("\n");
            promptBuilder.append("- 消息: ").append(message).append("\n\n");
            promptBuilder.append("请提供：\n");
            promptBuilder.append("1. **问题原因分析**（详细说明为什么会出现这个事件）\n");
            promptBuilder.append("2. **可能的影响**（这个事件对集群或应用有什么影响）\n");
            promptBuilder.append("3. **解决建议**（如何解决或预防这个问题）\n");
            promptBuilder.append("\n请用中文回答，使用 Markdown 格式，包括标题、列表、代码块等，使格式清晰易读。");
            
            // 调用AI分析
            String analysis = qwenService.chat(promptBuilder.toString());
            
            result.put("success", true);
            result.put("analysis", analysis);
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "AI分析失败: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    private void initClient() {
        if (kubeconfig != null && !kubeconfig.isEmpty()) {
            System.setProperty("kubeconfig", kubeconfig);
        }
        if (masterUrl != null && !masterUrl.isEmpty()) {
            System.setProperty("kubernetes.master", masterUrl);
        }
    }
}
