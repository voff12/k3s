package com.example.k3sdemo.model;

public class NodeDiskViewModel {
    private String name;
    private String path;
    private String mountPoint;
    private String usage; // e.g., "420GB / 800GB"
    private int usagePercentage;
    private String filesystem;
    private String status; // "正常" or "异常"

    public NodeDiskViewModel(String name, String path, String mountPoint, String usage, int usagePercentage,
            String filesystem, String status) {
        this.name = name;
        this.path = path;
        this.mountPoint = mountPoint;
        this.usage = usage;
        this.usagePercentage = usagePercentage;
        this.filesystem = filesystem;
        this.status = status;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public String getMountPoint() {
        return mountPoint;
    }

    public String getUsage() {
        return usage;
    }

    public int getUsagePercentage() {
        return usagePercentage;
    }

    public String getFilesystem() {
        return filesystem;
    }

    public String getStatus() {
        return status;
    }
}
