package com.example.k3sdemo.controller;

import com.example.k3sdemo.model.NodeDiskViewModel;
import com.example.k3sdemo.model.PvcViewModel;
import com.example.k3sdemo.model.StorageOverviewViewModel;
import com.example.k3sdemo.model.VolumeViewModel;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.List;

@Controller
public class StoreController {

    @org.springframework.beans.factory.annotation.Value("${k8s.master.url:}")
    private String masterUrl;

    @org.springframework.beans.factory.annotation.Value("${k8s.kubeconfig:}")
    private String kubeconfig;

    @GetMapping("/store")
    public String store(Model model) {
        initClient();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            // 1. Fetch Resources
            List<PersistentVolume> pvs = client.persistentVolumes().list().getItems();
            List<PersistentVolumeClaim> pvcList = client.persistentVolumeClaims().inAnyNamespace().list().getItems();

            List<Node> nodes = client.nodes().list().getItems();
            List<Pod> pods = client.pods().inAnyNamespace().list().getItems();

            // 2. Build VolumeViewModels
            List<VolumeViewModel> volumes = new ArrayList<>();
            for (PersistentVolume pv : pvs) {
                String name = pv.getMetadata().getName();
                String status = pv.getStatus().getPhase();
                String capacity = pv.getSpec().getCapacity().get("storage").getAmount();
                String accessMode = pv.getSpec().getAccessModes().isEmpty() ? "-"
                        : pv.getSpec().getAccessModes().get(0);

                // Find ClaimRef
                String claimRef = "";
                String mountedPod = "未挂载";

                if (pv.getSpec().getClaimRef() != null) {
                    claimRef = pv.getSpec().getClaimRef().getName();
                    String claimNs = pv.getSpec().getClaimRef().getNamespace();

                    String targetClaim = claimRef;
                    // Find Pods using this PVC
                    for (Pod pod : pods) {
                        if (pod.getSpec().getVolumes() != null) {
                            boolean isMounted = pod.getSpec().getVolumes().stream()
                                    .anyMatch(v -> v.getPersistentVolumeClaim() != null &&
                                            v.getPersistentVolumeClaim().getClaimName().equals(targetClaim));
                            if (isMounted && pod.getMetadata().getNamespace().equals(claimNs)) {
                                mountedPod = pod.getMetadata().getName();
                                break; // Just show one for demo
                            }
                        }
                    }
                }

                String statusColor = "bg-gray-100 text-gray-800";
                if ("Bound".equals(status)) {
                    statusColor = "bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400";
                } else if ("Released".equals(status)) {
                    statusColor = "bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400";
                }

                volumes.add(new VolumeViewModel(name, status, capacity, accessMode, mountedPod, statusColor));
            }

            // 3. Build NodeDiskViewModels and calculate total capacity
            List<NodeDiskViewModel> nodeDisks = new ArrayList<>();
            long totalCapacityBytes = 0; // Total capacity in bytes

            for (Node node : nodes) {
                String name = node.getMetadata().getName();
                String path = "/var/lib/rancher/k3s/storage"; // Default K3s local path
                String status = "正常";

                // Try to get capacity from allocatable or capacity
                String storageStr = "0";

                if (node.getStatus().getAllocatable().containsKey("ephemeral-storage")) {
                    storageStr = node.getStatus().getAllocatable().get("ephemeral-storage").getAmount();
                } else if (node.getStatus().getCapacity().containsKey("ephemeral-storage")) {
                    storageStr = node.getStatus().getCapacity().get("ephemeral-storage").getAmount();
                }

                // Parse storage string to bytes and accumulate
                long nodeCapacityBytes = parseQuantityToBytes(storageStr);
                totalCapacityBytes += nodeCapacityBytes;

                // Calculate used percentage (mock for now, since we don't have metrics server)
                long totalHash = name.hashCode();
                int usedPercent = Math.abs((int) (totalHash % 60)) + 20; // 20% to 80%

                String capacityDisplay = storageStr;

                // Assuming storageStr is something like "100Gi"
                nodeDisks.add(
                        new NodeDiskViewModel(name, path, "/dev/vda1", capacityDisplay, usedPercent, "ext4", status));
            }

            // 4. Build Overview with real total capacity
            String totalCapDisp = formatBytes(totalCapacityBytes);

            StorageOverviewViewModel overview = new StorageOverviewViewModel(
                    totalCapDisp,
                    volumes.size() + " Volumes",
                    "N/A",
                    nodes.size(),
                    nodes.size());

            // 5. Build PvcViewModels
            List<PvcViewModel> pvcs = new ArrayList<>();
            for (PersistentVolumeClaim pvc : pvcList) {
                String pvcName = pvc.getMetadata().getName();
                String pvcNs = pvc.getMetadata().getNamespace();
                String pvcStatus = pvc.getStatus().getPhase();
                String pvcCapacity = "-";
                if (pvc.getStatus().getCapacity() != null && pvc.getStatus().getCapacity().containsKey("storage")) {
                    pvcCapacity = pvc.getStatus().getCapacity().get("storage").getAmount() +
                            pvc.getStatus().getCapacity().get("storage").getFormat();
                } else if (pvc.getSpec().getResources().getRequests() != null &&
                        pvc.getSpec().getResources().getRequests().containsKey("storage")) {
                    pvcCapacity = pvc.getSpec().getResources().getRequests().get("storage").getAmount() +
                            pvc.getSpec().getResources().getRequests().get("storage").getFormat();
                }
                String pvcAccessMode = pvc.getSpec().getAccessModes().isEmpty() ? "-"
                        : pvc.getSpec().getAccessModes().get(0);
                String storageClass = pvc.getSpec().getStorageClassName() != null
                        ? pvc.getSpec().getStorageClassName()
                        : "-";
                String boundPv = pvc.getSpec().getVolumeName() != null
                        ? pvc.getSpec().getVolumeName()
                        : "未绑定";

                String pvcStatusColor = "bg-gray-100 text-gray-800";
                if ("Bound".equals(pvcStatus)) {
                    pvcStatusColor = "bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400";
                } else if ("Pending".equals(pvcStatus)) {
                    pvcStatusColor = "bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400";
                } else if ("Lost".equals(pvcStatus)) {
                    pvcStatusColor = "bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400";
                }

                pvcs.add(new PvcViewModel(pvcName, pvcNs, pvcStatus, pvcCapacity,
                        pvcAccessMode, storageClass, boundPv, pvcStatusColor));
            }

            model.addAttribute("overview", overview);
            model.addAttribute("volumes", volumes);
            model.addAttribute("pvcs", pvcs);
            model.addAttribute("nodes", nodeDisks);

        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("error", "Error fetching storage: " + e.getMessage());
        }
        return "store";
    }

    private void initClient() {
        if (kubeconfig != null && !kubeconfig.isEmpty()) {
            System.setProperty("kubeconfig", kubeconfig);
        }
        if (masterUrl != null && !masterUrl.isEmpty()) {
            System.setProperty("kubernetes.master", masterUrl);
        }
    }

    /**
     * Parse Kubernetes Quantity string to bytes
     * Supports: Ki, Mi, Gi, Ti (binary) and K, M, G, T (decimal)
     * Examples: "100Gi", "50Mi", "1Ti", "100G", "50M"
     */
    private long parseQuantityToBytes(String quantity) {
        if (quantity == null || quantity.isEmpty() || quantity.equals("0")) {
            return 0;
        }

        try {
            // Remove any whitespace
            quantity = quantity.trim();

            // Find where the number ends and unit begins
            // Look for the first non-digit, non-decimal point character
            int unitStart = -1;
            for (int i = 0; i < quantity.length(); i++) {
                char c = quantity.charAt(i);
                if (!Character.isDigit(c) && c != '.') {
                    unitStart = i;
                    break;
                }
            }

            if (unitStart == -1) {
                // No unit found, assume bytes
                return Long.parseLong(quantity);
            }

            String numberStr = quantity.substring(0, unitStart);
            String unit = quantity.substring(unitStart).trim();

            if (numberStr.isEmpty()) {
                return 0;
            }

            double number = Double.parseDouble(numberStr);

            // Convert to bytes based on unit
            switch (unit) {
                // Binary units (1024-based)
                case "Ki":
                    return (long) (number * 1024);
                case "Mi":
                    return (long) (number * 1024 * 1024);
                case "Gi":
                    return (long) (number * 1024 * 1024 * 1024);
                case "Ti":
                    return (long) (number * 1024L * 1024 * 1024 * 1024);
                case "Pi":
                    return (long) (number * 1024L * 1024 * 1024 * 1024 * 1024);
                case "Ei":
                    return (long) (number * 1024L * 1024 * 1024 * 1024 * 1024 * 1024);

                // Decimal units (1000-based)
                case "K":
                case "k":
                    return (long) (number * 1000);
                case "M":
                    return (long) (number * 1000 * 1000);
                case "G":
                    return (long) (number * 1000 * 1000 * 1000);
                case "T":
                    return (long) (number * 1000L * 1000 * 1000 * 1000);
                case "P":
                    return (long) (number * 1000L * 1000 * 1000 * 1000 * 1000);
                case "E":
                    return (long) (number * 1000L * 1000 * 1000 * 1000 * 1000 * 1000);

                // No unit or unknown, assume bytes
                default:
                    return (long) number;
            }
        } catch (Exception e) {
            System.err.println("Failed to parse quantity: " + quantity + ", error: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Format bytes to human-readable string
     * Examples: "100 GiB", "50 MiB", "1 TiB"
     */
    private String formatBytes(long bytes) {
        if (bytes == 0) {
            return "0 B";
        }

        String[] units = { "B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB" };
        int unitIndex = 0;
        double size = bytes;

        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }

        // Format to 2 decimal places, but remove trailing zeros
        if (size == (long) size) {
            return String.format("%d %s", (long) size, units[unitIndex]);
        } else {
            return String.format("%.2f %s", size, units[unitIndex]);
        }
    }
}
