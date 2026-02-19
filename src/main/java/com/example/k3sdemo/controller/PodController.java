package com.example.k3sdemo.controller;

import com.example.k3sdemo.model.PodViewModel;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Controller
public class PodController {

    @org.springframework.beans.factory.annotation.Value("${k8s.master.url:}")
    private String masterUrl;

    @org.springframework.beans.factory.annotation.Value("${k8s.kubeconfig:}")
    private String kubeconfig;

    @GetMapping("/")
    public String index(Model model,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String search,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String namespace) {
        initClient();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            String targetNamespace = (namespace != null && !namespace.isEmpty()) ? namespace : "default";
            List<Pod> podList = client.pods().inNamespace(targetNamespace).list().getItems();
            List<PodViewModel> pods = new ArrayList<>();

            for (Pod pod : podList) {
                String podName = pod.getMetadata().getName();
                String podStatus = pod.getStatus().getPhase();

                // Filter logic
                if (search != null && !search.isEmpty() && !podName.contains(search)
                        && (pod.getStatus().getPodIP() == null || !pod.getStatus().getPodIP().contains(search))) {
                    continue;
                }
                if (status != null && !status.isEmpty() && !status.equals(podStatus)) {
                    continue;
                }

                String podNamespace = pod.getMetadata().getNamespace();
                String ip = pod.getStatus().getPodIP();
                String node = pod.getSpec().getNodeName();

                // 计算重启次数 - 从所有容器状态中获取真实的重启次数
                int restarts = 0;
                if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
                    for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                        if (cs != null && cs.getRestartCount() != null) {
                            restarts += cs.getRestartCount();
                        }
                    }
                }
                // 如果容器状态为空，尝试从 init container 状态获取
                if (restarts == 0 && pod.getStatus() != null && pod.getStatus().getInitContainerStatuses() != null) {
                    for (ContainerStatus cs : pod.getStatus().getInitContainerStatuses()) {
                        if (cs != null && cs.getRestartCount() != null) {
                            restarts += cs.getRestartCount();
                        }
                    }
                }

                String age = calculateAge(pod.getStatus().getStartTime());

                pods.add(new PodViewModel(podName, podNamespace, podStatus, ip != null ? ip : "--",
                        node != null ? node : "--",
                        restarts, age));
            }

            model.addAttribute("pods", pods);
            model.addAttribute("search", search);
            model.addAttribute("status", status);
            model.addAttribute("namespace", targetNamespace);

            // Populate filter options (mock for now, could be dynamic)
            model.addAttribute("namespaces", List.of("default", "kube-system", "harbor"));

        } catch (Exception e) {
            model.addAttribute("error", "Failed to connect to Kubernetes: " + e.getMessage());
            model.addAttribute("pods", List.of());
            e.printStackTrace();
        }
        return "pods";
    }

    @org.springframework.web.bind.annotation.PostMapping("/deploy")
    public String createDeployment(@org.springframework.web.bind.annotation.RequestParam String name,
            @org.springframework.web.bind.annotation.RequestParam String image,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int replicas,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "80") int port,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "default") String namespace) {
        initClient();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            io.fabric8.kubernetes.api.model.apps.Deployment deployment = new io.fabric8.kubernetes.api.model.apps.DeploymentBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .endMetadata()
                    .withNewSpec()
                    .withReplicas(replicas)
                    .withNewSelector()
                    .addToMatchLabels("app", name)
                    .endSelector()
                    .withNewTemplate()
                    .withNewMetadata()
                    .addToLabels("app", name)
                    .endMetadata()
                    .withNewSpec()
                    .addNewContainer()
                    .withName(name)
                    .withImage(image)
                    .addNewPort()
                    .withContainerPort(port)
                    .endPort()
                    .endContainer()
                    .endSpec()
                    .endTemplate()
                    .endSpec()
                    .build();

            client.apps().deployments().inNamespace(namespace).resource(deployment).create();
        } catch (Exception e) {
            e.printStackTrace();
            return "redirect:/?error=Failed to deploy " + name;
        }
        return "redirect:/?namespace=" + namespace;
    }

    private String calculateAge(String startTime) {
        if (startTime == null)
            return "Unknown";
        try {
            Instant start = Instant.parse(startTime);
            Duration d = Duration.between(start, Instant.now());
            long days = d.toDays();
            if (days > 0)
                return days + "d";
            long hours = d.toHours();
            if (hours > 0)
                return hours + "h";
            return d.toMinutes() + "m";
        } catch (DateTimeParseException e) {
            return "Unknown";
        }
    }

    @GetMapping("/pods/{namespace}/{name}")
    public String getPodDetail(@org.springframework.web.bind.annotation.PathVariable String namespace,
            @org.springframework.web.bind.annotation.PathVariable String name,
            Model model) {
        initClient();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Pod pod = client.pods().inNamespace(namespace).withName(name).get();
            if (pod == null) {
                return "redirect:/?error=Pod not found";
            }

            String podName = pod.getMetadata().getName();
            String podNamespace = pod.getMetadata().getNamespace();
            String status = pod.getStatus().getPhase();
            String ip = pod.getStatus().getPodIP();
            String node = pod.getSpec().getNodeName();
            String age = calculateAge(pod.getStatus().getStartTime());

            // 计算重启次数 - 从所有容器状态中获取真实的重启次数
            int restarts = 0;
            java.util.Map<String, Integer> containerRestarts = new java.util.HashMap<>();

            if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
                for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                    if (cs != null && cs.getName() != null && cs.getRestartCount() != null) {
                        int restartCount = cs.getRestartCount();
                        containerRestarts.put(cs.getName(), restartCount);
                        restarts += restartCount;
                    }
                }
            }
            // 如果容器状态为空，尝试从 init container 状态获取
            if (pod.getStatus() != null && pod.getStatus().getInitContainerStatuses() != null) {
                for (ContainerStatus cs : pod.getStatus().getInitContainerStatuses()) {
                    if (cs != null && cs.getName() != null && cs.getRestartCount() != null) {
                        int restartCount = cs.getRestartCount();
                        containerRestarts.put(cs.getName() + " (init)", restartCount);
                        restarts += restartCount;
                    }
                }
            }

            // Fetch Logs (tail last 100 lines)
            String logs = "";
            try {
                logs = client.pods().inNamespace(namespace).withName(name).tailingLines(100).getLog();
            } catch (Exception e) {
                logs = "Failed to fetch logs: " + e.getMessage();
            }

            // Serialize YAML
            String yaml = "";
            try {
                yaml = io.fabric8.kubernetes.client.utils.Serialization.asYaml(pod);
            } catch (Exception e) {
                yaml = "Failed to serialize YAML: " + e.getMessage();
            }

            List<String> containers = new ArrayList<>();
            if (pod.getSpec().getContainers() != null) {
                pod.getSpec().getContainers().forEach(c -> containers.add(c.getName()));
            }

            PodViewModel podViewModel = new PodViewModel(podName, podNamespace, status, ip != null ? ip : "--",
                    node != null ? node : "--", restarts, age, logs, yaml, containers, containerRestarts);

            model.addAttribute("pod", podViewModel);

        } catch (Exception e) {
            e.printStackTrace();
            return "redirect:/?error=Error fetching details: " + e.getMessage();
        }
        return "poddetail";
    }

    @org.springframework.web.bind.annotation.GetMapping("/pods/{namespace}/{name}/logs")
    @org.springframework.web.bind.annotation.ResponseBody
    public String getPodLogs(@org.springframework.web.bind.annotation.PathVariable String namespace,
            @org.springframework.web.bind.annotation.PathVariable String name,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String keyword,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "500") int lines) {
        initClient();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            String logs = client.pods().inNamespace(namespace).withName(name).tailingLines(lines).getLog();
            if (keyword != null && !keyword.isEmpty()) {
                // Simple case-insensitive line filtering
                return java.util.Arrays.stream(logs.split("\n"))
                        .filter(line -> line.toLowerCase().contains(keyword.toLowerCase()))
                        .collect(java.util.stream.Collectors.joining("\n"));
            }
            return logs;
        } catch (Exception e) {
            return "Failed to fetch logs: " + e.getMessage();
        }
    }

    @org.springframework.web.bind.annotation.GetMapping("/pods/{namespace}/{name}/yaml")
    @org.springframework.web.bind.annotation.ResponseBody
    public String getPodYaml(@org.springframework.web.bind.annotation.PathVariable String namespace,
            @org.springframework.web.bind.annotation.PathVariable String name) {
        initClient();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Pod pod = client.pods().inNamespace(namespace).withName(name).get();
            if (pod == null) {
                return "Pod not found";
            }
            return io.fabric8.kubernetes.client.utils.Serialization.asYaml(pod);
        } catch (Exception e) {
            return "Failed to fetch YAML: " + e.getMessage();
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/pods/{namespace}/{name}/delete")
    public String deletePod(@org.springframework.web.bind.annotation.PathVariable String namespace,
            @org.springframework.web.bind.annotation.PathVariable String name) {
        initClient();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            client.pods().inNamespace(namespace).withName(name).delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "redirect:/";
    }

    @org.springframework.web.bind.annotation.PostMapping("/pods/{namespace}/{name}/scale")
    public String scaleDeployment(@org.springframework.web.bind.annotation.PathVariable String namespace,
            @org.springframework.web.bind.annotation.PathVariable String name,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String action,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer replicas) {
        initClient();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Pod pod = client.pods().inNamespace(namespace).withName(name).get();
            if (pod != null) {
                String deploymentName = getDeploymentName(client, pod);
                if (deploymentName != null) {
                    io.fabric8.kubernetes.api.model.apps.Deployment deployment = client.apps().deployments()
                            .inNamespace(namespace).withName(deploymentName).get();
                    if (deployment != null) {
                        int targetReplicas;
                        if (replicas != null) {
                            // 使用指定的副本数
                            targetReplicas = replicas;
                        } else if ("stop".equals(action)) {
                            targetReplicas = 0;
                        } else if ("start".equals(action)) {
                            // 尝试恢复之前的副本数，如果没有则设为1
                            Integer currentReplicas = deployment.getSpec().getReplicas();
                            targetReplicas = (currentReplicas != null && currentReplicas > 0) ? currentReplicas : 1;
                        } else {
                            // 默认保持当前副本数
                            Integer currentReplicas = deployment.getSpec().getReplicas();
                            targetReplicas = (currentReplicas != null) ? currentReplicas : 1;
                        }
                        client.apps().deployments().inNamespace(namespace).withName(deploymentName)
                                .scale(targetReplicas);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "redirect:/pods/" + namespace + "/" + name;
    }

    @org.springframework.web.bind.annotation.PostMapping("/pods/{namespace}/{name}/update")
    public String updateDeployment(@org.springframework.web.bind.annotation.PathVariable String namespace,
            @org.springframework.web.bind.annotation.PathVariable String name,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String image,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String cpuLimit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String memoryLimit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String cpuRequest,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String memoryRequest,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String envVars) {
        initClient();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Pod pod = client.pods().inNamespace(namespace).withName(name).get();
            if (pod != null) {
                String deploymentName = getDeploymentName(client, pod);
                if (deploymentName != null) {
                    io.fabric8.kubernetes.api.model.apps.Deployment deployment = client.apps().deployments()
                            .inNamespace(namespace).withName(deploymentName).get();
                    if (deployment != null) {
                        // 编辑 Deployment
                        // 更新镜像
                        if (image != null && !image.isEmpty()) {
                            if (deployment.getSpec().getTemplate().getSpec().getContainers() != null
                                    && !deployment.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
                                deployment.getSpec().getTemplate().getSpec().getContainers().get(0).setImage(image);
                            }
                        }

                        // 更新资源限制和请求
                        if (deployment.getSpec().getTemplate().getSpec().getContainers() != null
                                && !deployment.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
                            io.fabric8.kubernetes.api.model.Container container = deployment.getSpec().getTemplate()
                                    .getSpec().getContainers().get(0);

                            io.fabric8.kubernetes.api.model.ResourceRequirements resources = container.getResources();
                            if (resources == null) {
                                resources = new io.fabric8.kubernetes.api.model.ResourceRequirements();
                            }

                            // 设置资源限制
                            if (cpuLimit != null && !cpuLimit.isEmpty()
                                    || memoryLimit != null && !memoryLimit.isEmpty()) {
                                java.util.Map<String, io.fabric8.kubernetes.api.model.Quantity> limits = resources
                                        .getLimits();
                                if (limits == null) {
                                    limits = new java.util.HashMap<>();
                                }
                                if (cpuLimit != null && !cpuLimit.isEmpty()) {
                                    limits.put("cpu", new io.fabric8.kubernetes.api.model.Quantity(cpuLimit));
                                }
                                if (memoryLimit != null && !memoryLimit.isEmpty()) {
                                    limits.put("memory", new io.fabric8.kubernetes.api.model.Quantity(memoryLimit));
                                }
                                resources.setLimits(limits);
                            }

                            // 设置资源请求
                            if (cpuRequest != null && !cpuRequest.isEmpty()
                                    || memoryRequest != null && !memoryRequest.isEmpty()) {
                                java.util.Map<String, io.fabric8.kubernetes.api.model.Quantity> requests = resources
                                        .getRequests();
                                if (requests == null) {
                                    requests = new java.util.HashMap<>();
                                }
                                if (cpuRequest != null && !cpuRequest.isEmpty()) {
                                    requests.put("cpu", new io.fabric8.kubernetes.api.model.Quantity(cpuRequest));
                                }
                                if (memoryRequest != null && !memoryRequest.isEmpty()) {
                                    requests.put("memory", new io.fabric8.kubernetes.api.model.Quantity(memoryRequest));
                                }
                                resources.setRequests(requests);
                            }

                            container.setResources(resources);

                            // 更新环境变量
                            if (envVars != null && !envVars.isEmpty()) {
                                List<io.fabric8.kubernetes.api.model.EnvVar> envList = new ArrayList<>();
                                String[] pairs = envVars.split(",");
                                for (String pair : pairs) {
                                    String[] kv = pair.split("=", 2);
                                    if (kv.length == 2) {
                                        envList.add(new io.fabric8.kubernetes.api.model.EnvVarBuilder()
                                                .withName(kv[0].trim())
                                                .withValue(kv[1].trim())
                                                .build());
                                    }
                                }
                                if (!envList.isEmpty()) {
                                    container.setEnv(envList);
                                }
                            }
                        }

                        // 更新 Deployment
                        client.apps().deployments().inNamespace(namespace).resource(deployment).update();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "redirect:/pods/" + namespace + "/" + name;
    }

    @org.springframework.web.bind.annotation.GetMapping("/pods/{namespace}/{name}/deployment")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.Map<String, Object> getDeploymentInfo(
            @org.springframework.web.bind.annotation.PathVariable String namespace,
            @org.springframework.web.bind.annotation.PathVariable String name) {
        initClient();
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Pod pod = client.pods().inNamespace(namespace).withName(name).get();
            if (pod != null) {
                String deploymentName = getDeploymentName(client, pod);
                if (deploymentName != null) {
                    io.fabric8.kubernetes.api.model.apps.Deployment deployment = client.apps().deployments()
                            .inNamespace(namespace).withName(deploymentName).get();
                    if (deployment != null) {
                        result.put("deploymentName", deploymentName);
                        result.put("replicas", deployment.getSpec().getReplicas());
                        result.put("availableReplicas", deployment.getStatus().getAvailableReplicas());

                        if (deployment.getSpec().getTemplate().getSpec().getContainers() != null
                                && !deployment.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
                            io.fabric8.kubernetes.api.model.Container container = deployment.getSpec().getTemplate()
                                    .getSpec().getContainers().get(0);
                            result.put("image", container.getImage());

                            io.fabric8.kubernetes.api.model.ResourceRequirements resources = container.getResources();
                            if (resources != null) {
                                if (resources.getLimits() != null) {
                                    result.put("cpuLimit", resources.getLimits().get("cpu") != null
                                            ? resources.getLimits().get("cpu").getAmount()
                                            : null);
                                    result.put("memoryLimit", resources.getLimits().get("memory") != null
                                            ? resources.getLimits().get("memory").getAmount()
                                            : null);
                                }
                                if (resources.getRequests() != null) {
                                    result.put("cpuRequest", resources.getRequests().get("cpu") != null
                                            ? resources.getRequests().get("cpu").getAmount()
                                            : null);
                                    result.put("memoryRequest", resources.getRequests().get("memory") != null
                                            ? resources.getRequests().get("memory").getAmount()
                                            : null);
                                }
                            }

                            if (container.getEnv() != null) {
                                java.util.Map<String, String> envMap = new java.util.HashMap<>();
                                for (io.fabric8.kubernetes.api.model.EnvVar env : container.getEnv()) {
                                    envMap.put(env.getName(), env.getValue());
                                }
                                result.put("envVars", envMap);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            result.put("error", e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    @GetMapping("/pods/{namespace}/{name}/terminal")
    public String getPodTerminal(@org.springframework.web.bind.annotation.PathVariable String namespace,
            @org.springframework.web.bind.annotation.PathVariable String name,
            Model model) {
        initClient();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Pod pod = client.pods().inNamespace(namespace).withName(name).get();
            if (pod == null) {
                return "redirect:/?error=Pod not found";
            }

            String podName = pod.getMetadata().getName();
            String podNamespace = pod.getMetadata().getNamespace();
            String status = pod.getStatus().getPhase();

            List<String> containers = new ArrayList<>();
            if (pod.getSpec().getContainers() != null) {
                pod.getSpec().getContainers().forEach(c -> containers.add(c.getName()));
            }

            // Minimal ViewModel for terminal
            PodViewModel podViewModel = new PodViewModel(podName, podNamespace, status, null, null, 0, null, null, null,
                    containers);
            model.addAttribute("pod", podViewModel);

        } catch (Exception e) {
            e.printStackTrace();
            return "redirect:/?error=Error fetching details: " + e.getMessage();
        }
        return "podterm";
    }

    private void initClient() {
        if (kubeconfig != null && !kubeconfig.isEmpty()) {
            System.setProperty("kubeconfig", kubeconfig);
        }
        if (masterUrl != null && !masterUrl.isEmpty()) {
            System.setProperty("kubernetes.master", masterUrl);
        }
    }

    private String getDeploymentName(KubernetesClient client, Pod pod) {
        if (pod.getMetadata().getOwnerReferences() != null) {
            for (io.fabric8.kubernetes.api.model.OwnerReference ref : pod.getMetadata().getOwnerReferences()) {
                if ("ReplicaSet".equals(ref.getKind())) {
                    io.fabric8.kubernetes.api.model.apps.ReplicaSet rs = client.apps().replicaSets()
                            .inNamespace(pod.getMetadata().getNamespace()).withName(ref.getName()).get();
                    if (rs != null && rs.getMetadata().getOwnerReferences() != null) {
                        for (io.fabric8.kubernetes.api.model.OwnerReference rsRef : rs.getMetadata()
                                .getOwnerReferences()) {
                            if ("Deployment".equals(rsRef.getKind())) {
                                return rsRef.getName();
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
