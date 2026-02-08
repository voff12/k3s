package com.example.k3sdemo.model;

public class MemoryOverviewViewModel {
    private String totalMemory;
    private String allocatedMemory;
    private String utilization;

    public MemoryOverviewViewModel(String totalMemory, String allocatedMemory, String utilization) {
        this.totalMemory = totalMemory;
        this.allocatedMemory = allocatedMemory;
        this.utilization = utilization;
    }

    public String getTotalMemory() {
        return totalMemory;
    }

    public String getAllocatedMemory() {
        return allocatedMemory;
    }

    public String getUtilization() {
        return utilization;
    }
}
