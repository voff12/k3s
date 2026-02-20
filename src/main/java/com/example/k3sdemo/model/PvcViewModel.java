package com.example.k3sdemo.model;

public class PvcViewModel {
    private String name;
    private String namespace;
    private String status;
    private String capacity;
    private String accessMode;
    private String storageClass;
    private String volumeName; // 绑定的 PV 名称
    private String statusColor;

    public PvcViewModel(String name, String namespace, String status, String capacity,
            String accessMode, String storageClass, String volumeName, String statusColor) {
        this.name = name;
        this.namespace = namespace;
        this.status = status;
        this.capacity = capacity;
        this.accessMode = accessMode;
        this.storageClass = storageClass;
        this.volumeName = volumeName;
        this.statusColor = statusColor;
    }

    public String getName() {
        return name;
    }

    public String getNamespace() {
        return namespace;
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

    public String getStorageClass() {
        return storageClass;
    }

    public String getVolumeName() {
        return volumeName;
    }

    public String getStatusColor() {
        return statusColor;
    }
}
