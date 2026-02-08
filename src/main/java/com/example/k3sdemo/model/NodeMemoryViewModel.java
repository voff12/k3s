package com.example.k3sdemo.model;

public class NodeMemoryViewModel {
    private String nodeName;
    private String totalMemory;
    private String allocatedMemory;
    private double utilization;
    private double displayUtilization; // 用于显示的利用率（最小10%以便可视化）

    public NodeMemoryViewModel(String nodeName, String totalMemory, String allocatedMemory, double utilization) {
        this.nodeName = nodeName;
        this.totalMemory = totalMemory;
        this.allocatedMemory = allocatedMemory;
        this.utilization = utilization;
        // 如果利用率太小，设置为10%以便可视化
        this.displayUtilization = utilization < 1.0 ? 10.0 : Math.min(100, utilization);
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getTotalMemory() {
        return totalMemory;
    }

    public String getAllocatedMemory() {
        return allocatedMemory;
    }

    public double getUtilization() {
        return utilization;
    }

    public double getDisplayUtilization() {
        return displayUtilization;
    }
}
