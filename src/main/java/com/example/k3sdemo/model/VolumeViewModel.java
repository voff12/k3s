package com.example.k3sdemo.model;

public class VolumeViewModel {
    private String name;
    private String status;
    private String capacity;
    private String accessMode;
    private String mountedPod;
    private String statusColor; // e.g., "bg-green-100 text-green-800"

    public VolumeViewModel(String name, String status, String capacity, String accessMode, String mountedPod,
            String statusColor) {
        this.name = name;
        this.status = status;
        this.capacity = capacity;
        this.accessMode = accessMode;
        this.mountedPod = mountedPod;
        this.statusColor = statusColor;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public String getCapacity() {
        return capacity;
    }

    public String getAccessMode() {
        return accessMode;
    }

    public String getMountedPod() {
        return mountedPod;
    }

    public String getStatusColor() {
        return statusColor;
    }
}
