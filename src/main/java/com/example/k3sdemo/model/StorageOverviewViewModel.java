package com.example.k3sdemo.model;

public class StorageOverviewViewModel {
    private String totalCapacity;
    private String usedSpace;
    private String usagePercentage;
    private int healthyDisks;
    private int totalDisks;

    public StorageOverviewViewModel(String totalCapacity, String usedSpace, String usagePercentage, int healthyDisks,
            int totalDisks) {
        this.totalCapacity = totalCapacity;
        this.usedSpace = usedSpace;
        this.usagePercentage = usagePercentage;
        this.healthyDisks = healthyDisks;
        this.totalDisks = totalDisks;
    }

    public String getTotalCapacity() {
        return totalCapacity;
    }

    public String getUsedSpace() {
        return usedSpace;
    }

    public String getUsagePercentage() {
        return usagePercentage;
    }

    public int getHealthyDisks() {
        return healthyDisks;
    }

    public int getTotalDisks() {
        return totalDisks;
    }
}
