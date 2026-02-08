package com.example.k3sdemo.model;

public class PodMemoryViewModel {
    private String podName;
    private String namespace;
    private String memoryRequest;
    private String memoryLimit;
    private String actualUsage;
    private double usagePercent;
    private String status;
    private String statusClass;

    public PodMemoryViewModel(String podName, String namespace, String memoryRequest, 
                             String memoryLimit, String actualUsage, double usagePercent,
                             String status, String statusClass) {
        this.podName = podName;
        this.namespace = namespace;
        this.memoryRequest = memoryRequest;
        this.memoryLimit = memoryLimit;
        this.actualUsage = actualUsage;
        this.usagePercent = usagePercent;
        this.status = status;
        this.statusClass = statusClass;
    }

    public String getPodName() {
        return podName;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getMemoryRequest() {
        return memoryRequest;
    }

    public String getMemoryLimit() {
        return memoryLimit;
    }

    public String getActualUsage() {
        return actualUsage;
    }

    public double getUsagePercent() {
        return usagePercent;
    }

    public String getStatus() {
        return status;
    }

    public String getStatusClass() {
        return statusClass;
    }
}
