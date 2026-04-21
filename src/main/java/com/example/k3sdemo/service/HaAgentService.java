package com.example.k3sdemo.service;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Collects K3s cluster health data for HA diagnostics.
 * Checks for: high-restart pods, missing resource limits/requests,
 * missing liveness/readiness probes, single-replica deployments,
 * missing PodDisruptionBudgets, and unhealthy Ingress controller pods.
 */
@Service
public class HaAgentService {

    private static final int HIGH_RESTART_THRESHOLD = 5;

    @Value("${k8s.master.url:}")
    private String masterUrl;

    @Value("${k8s.kubeconfig:}")
    private String kubeconfig;

    /**
     * Scans the entire cluster and returns a structured diagnostic report
     * ready to be fed into the Qwen AI model.
     */
    public ClusterHealthReport scan() {
        initClient();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            return buildReport(client);
        }
    }

    private ClusterHealthReport buildReport(KubernetesClient client) {
        ClusterHealthReport report = new ClusterHealthReport();

        // ── Nodes ──────────────────────────────────────────────────────────
        List<Node> nodes = client.nodes().list().getItems();
        for (Node node : nodes) {
            NodeInfo ni = new NodeInfo();
            ni.name = node.getMetadata().getName();
            ni.ready = node.getStatus().getConditions().stream()
                    .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
            ni.memoryPressure = node.getStatus().getConditions().stream()
                    .anyMatch(c -> "MemoryPressure".equals(c.getType()) && "True".equals(c.getStatus()));
            ni.diskPressure = node.getStatus().getConditions().stream()
                    .anyMatch(c -> "DiskPressure".equals(c.getType()) && "True".equals(c.getStatus()));
            ni.pidPressure = node.getStatus().getConditions().stream()
                    .anyMatch(c -> "PIDPressure".equals(c.getType()) && "True".equals(c.getStatus()));
            report.nodes.add(ni);
        }

        // ── Pods (all namespaces) ──────────────────────────────────────────
        List<Pod> pods = client.pods().inAnyNamespace().list().getItems();
        for (Pod pod : pods) {
            String podName = pod.getMetadata().getName();
            String ns = pod.getMetadata().getNamespace();
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";

            int totalRestarts = 0;
            boolean hasOomKilled = false;
            if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
                for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                    if (cs.getRestartCount() != null) totalRestarts += cs.getRestartCount();
                    if (cs.getLastState() != null && cs.getLastState().getTerminated() != null) {
                        String reason = cs.getLastState().getTerminated().getReason();
                        if ("OOMKilled".equals(reason)) hasOomKilled = true;
                    }
                }
            }

            if (totalRestarts >= HIGH_RESTART_THRESHOLD || !"Running".equals(phase)) {
                PodIssue issue = new PodIssue();
                issue.name = podName;
                issue.namespace = ns;
                issue.phase = phase;
                issue.restarts = totalRestarts;
                issue.oomKilled = hasOomKilled;
                report.podIssues.add(issue);
            }
        }

        // ── Deployments (all namespaces) ───────────────────────────────────
        List<Deployment> deployments = client.apps().deployments().inAnyNamespace().list().getItems();

        // Collect PDB selectors for later lookup
        List<PodDisruptionBudget> pdbs = new ArrayList<>();
        try {
            pdbs = client.policy().v1().podDisruptionBudget().inAnyNamespace().list().getItems();
        } catch (Exception ignored) {
            // PDB API may not be available in all k3s versions
        }
        Set<String> pdbNamespaces = pdbs.stream()
                .map(p -> p.getMetadata().getNamespace())
                .collect(Collectors.toSet());

        for (Deployment dep : deployments) {
            String depName = dep.getMetadata().getName();
            String ns = dep.getMetadata().getNamespace();
            int replicas = dep.getSpec().getReplicas() != null ? dep.getSpec().getReplicas() : 1;

            DeploymentIssue issue = new DeploymentIssue();
            issue.name = depName;
            issue.namespace = ns;
            issue.replicas = replicas;
            issue.singleReplica = replicas < 2;

            // Check containers for missing resources/probes
            List<Container> containers = dep.getSpec().getTemplate().getSpec().getContainers();
            for (Container c : containers) {
                if (c.getResources() == null
                        || c.getResources().getRequests() == null
                        || c.getResources().getRequests().isEmpty()) {
                    issue.missingResourceRequests = true;
                }
                if (c.getResources() == null
                        || c.getResources().getLimits() == null
                        || c.getResources().getLimits().isEmpty()) {
                    issue.missingResourceLimits = true;
                }
                if (c.getLivenessProbe() == null) {
                    issue.missingLivenessProbe = true;
                }
                if (c.getReadinessProbe() == null) {
                    issue.missingReadinessProbe = true;
                }
            }

            // Check PDB: look for a PDB in the same namespace that matches this deployment's labels
            Map<String, String> depLabels = dep.getSpec().getSelector() != null
                    ? dep.getSpec().getSelector().getMatchLabels()
                    : Collections.emptyMap();
            boolean hasPdb = pdbs.stream().anyMatch(pdb -> {
                if (!ns.equals(pdb.getMetadata().getNamespace())) return false;
                Map<String, String> sel = pdb.getSpec().getSelector() != null
                        ? pdb.getSpec().getSelector().getMatchLabels()
                        : Collections.emptyMap();
                // The PDB matches if every entry in the PDB selector is present in the deployment labels
                return depLabels != null && !sel.isEmpty()
                        && sel.entrySet().stream()
                                .allMatch(e -> e.getValue().equals(depLabels.get(e.getKey())));
            });
            issue.missingPdb = !hasPdb && replicas >= 2; // PDB only useful with multiple replicas

            if (issue.singleReplica || issue.missingResourceRequests || issue.missingResourceLimits
                    || issue.missingLivenessProbe || issue.missingReadinessProbe || issue.missingPdb) {
                report.deploymentIssues.add(issue);
            }
        }

        // ── Ingress ────────────────────────────────────────────────────────
        try {
            List<Ingress> ingresses = client.network().v1().ingresses().inAnyNamespace().list().getItems();
            report.totalIngresses = ingresses.size();
        } catch (Exception ignored) {
            report.totalIngresses = 0;
        }

        // Check Traefik / ingress-nginx controller pods
        List<String> ingressControllerLabels = List.of(
                "app.kubernetes.io/name=traefik",
                "app=traefik",
                "app=ingress-nginx",
                "app.kubernetes.io/name=ingress-nginx");

        for (Pod pod : pods) {
            Map<String, String> labels = pod.getMetadata().getLabels() != null
                    ? pod.getMetadata().getLabels()
                    : Collections.emptyMap();
            boolean isIngress = labels.entrySet().stream().anyMatch(e -> {
                String kv = e.getKey() + "=" + e.getValue();
                return ingressControllerLabels.contains(kv);
            });
            if (isIngress) {
                String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
                IngressControllerInfo ic = new IngressControllerInfo();
                ic.name = pod.getMetadata().getName();
                ic.namespace = pod.getMetadata().getNamespace();
                ic.phase = phase;
                report.ingressControllers.add(ic);
            }
        }

        // ── Summary stats ──────────────────────────────────────────────────
        report.totalNodes = nodes.size();
        report.readyNodes = (int) report.nodes.stream().filter(n -> n.ready).count();
        report.totalPods = pods.size();
        report.runningPods = (int) pods.stream()
                .filter(p -> "Running".equals(p.getStatus() != null ? p.getStatus().getPhase() : ""))
                .count();
        report.totalDeployments = deployments.size();

        return report;
    }

    /**
     * Builds a structured prompt string from the collected report,
     * ready to be sent to Qwen for HA analysis.
     */
    public String buildPrompt(ClusterHealthReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位 Kubernetes/K3s 高可用专家。请根据以下自动采集的集群诊断数据，");
        sb.append("识别所有高可用风险，并给出具体的优化建议（含 YAML 示例）。\n\n");

        sb.append("## 集群概览\n");
        sb.append("- 节点总数: ").append(report.totalNodes).append("，就绪: ").append(report.readyNodes).append("\n");
        sb.append("- Pod 总数: ").append(report.totalPods).append("，运行中: ").append(report.runningPods).append("\n");
        sb.append("- Deployment 总数: ").append(report.totalDeployments).append("\n");
        sb.append("- Ingress 总数: ").append(report.totalIngresses).append("\n\n");

        // Nodes
        List<NodeInfo> problematicNodes = report.nodes.stream()
                .filter(n -> !n.ready || n.memoryPressure || n.diskPressure || n.pidPressure)
                .collect(Collectors.toList());
        if (!problematicNodes.isEmpty()) {
            sb.append("## 节点异常\n");
            for (NodeInfo n : problematicNodes) {
                sb.append("- **").append(n.name).append("**: ");
                List<String> issues = new ArrayList<>();
                if (!n.ready) issues.add("NotReady");
                if (n.memoryPressure) issues.add("MemoryPressure");
                if (n.diskPressure) issues.add("DiskPressure");
                if (n.pidPressure) issues.add("PIDPressure");
                sb.append(String.join(", ", issues)).append("\n");
            }
            sb.append("\n");
        }

        // Pod issues
        if (!report.podIssues.isEmpty()) {
            sb.append("## 问题 Pod（高重启 / 非 Running 状态）\n");
            for (PodIssue p : report.podIssues) {
                sb.append("- **").append(p.namespace).append("/").append(p.name).append("**");
                sb.append("  状态=").append(p.phase);
                sb.append("  重启次数=").append(p.restarts);
                if (p.oomKilled) sb.append("  ⚠️OOMKilled");
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Deployment issues
        if (!report.deploymentIssues.isEmpty()) {
            sb.append("## Deployment 配置风险\n");
            for (DeploymentIssue d : report.deploymentIssues) {
                sb.append("- **").append(d.namespace).append("/").append(d.name).append("**");
                sb.append("  副本数=").append(d.replicas);
                List<String> risks = new ArrayList<>();
                if (d.singleReplica) risks.add("单副本(无冗余)");
                if (d.missingResourceRequests) risks.add("缺resources.requests");
                if (d.missingResourceLimits) risks.add("缺resources.limits");
                if (d.missingLivenessProbe) risks.add("缺livenessProbe");
                if (d.missingReadinessProbe) risks.add("缺readinessProbe");
                if (d.missingPdb) risks.add("缺PodDisruptionBudget");
                sb.append("  风险: ").append(String.join(", ", risks)).append("\n");
            }
            sb.append("\n");
        }

        // Ingress controllers
        if (!report.ingressControllers.isEmpty()) {
            sb.append("## Ingress Controller 状态\n");
            for (IngressControllerInfo ic : report.ingressControllers) {
                sb.append("- **").append(ic.namespace).append("/").append(ic.name).append("**");
                sb.append("  状态=").append(ic.phase).append("\n");
            }
            sb.append("\n");
        }

        if (report.podIssues.isEmpty() && report.deploymentIssues.isEmpty() && problematicNodes.isEmpty()) {
            sb.append("## 初步结论\n当前集群未发现明显高可用风险，请进一步分析优化空间。\n\n");
        }

        sb.append("---\n");
        sb.append("请按以下结构回答（中文，Markdown 格式）：\n");
        sb.append("1. **风险总结**（按严重程度排序）\n");
        sb.append("2. **每个风险的根因分析**\n");
        sb.append("3. **针对每个风险的优化建议**（含具体 YAML 配置示例）\n");
        sb.append("4. **优先处理顺序建议**\n");

        return sb.toString();
    }

    private void initClient() {
        if (kubeconfig != null && !kubeconfig.isEmpty()) {
            System.setProperty("kubeconfig", kubeconfig);
        }
        if (masterUrl != null && !masterUrl.isEmpty()) {
            System.setProperty("kubernetes.master", masterUrl);
        }
    }

    // ── Data model ──────────────────────────────────────────────────────────

    public static class ClusterHealthReport {
        public int totalNodes;
        public int readyNodes;
        public int totalPods;
        public int runningPods;
        public int totalDeployments;
        public int totalIngresses;

        public List<NodeInfo> nodes = new ArrayList<>();
        public List<PodIssue> podIssues = new ArrayList<>();
        public List<DeploymentIssue> deploymentIssues = new ArrayList<>();
        public List<IngressControllerInfo> ingressControllers = new ArrayList<>();
    }

    public static class NodeInfo {
        public String name;
        public boolean ready;
        public boolean memoryPressure;
        public boolean diskPressure;
        public boolean pidPressure;
    }

    public static class PodIssue {
        public String name;
        public String namespace;
        public String phase;
        public int restarts;
        public boolean oomKilled;
    }

    public static class DeploymentIssue {
        public String name;
        public String namespace;
        public int replicas;
        public boolean singleReplica;
        public boolean missingResourceRequests;
        public boolean missingResourceLimits;
        public boolean missingLivenessProbe;
        public boolean missingReadinessProbe;
        public boolean missingPdb;
    }

    public static class IngressControllerInfo {
        public String name;
        public String namespace;
        public String phase;
    }
}
