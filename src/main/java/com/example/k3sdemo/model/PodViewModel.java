package com.example.k3sdemo.model;

public class PodViewModel {
    private String name;
    private String namespace;
    private String status;
    private String ip;
    private String node;
    private int restarts;
    private String age;

    private String logs;
    private String yaml;
    private java.util.List<String> containers;
    private java.util.Map<String, Integer> containerRestarts; // 每个容器的重启次数

    public PodViewModel(String name, String namespace, String status, String ip, String node, int restarts,
            String age) {
        this(name, namespace, status, ip, node, restarts, age, null, null, new java.util.ArrayList<>());
    }

    public PodViewModel(String name, String namespace, String status, String ip, String node, int restarts,
            String age, String logs, String yaml) {
        this(name, namespace, status, ip, node, restarts, age, logs, yaml, new java.util.ArrayList<>());
    }

    public PodViewModel(String name, String namespace, String status, String ip, String node, int restarts,
            String age, String logs, String yaml, java.util.List<String> containers) {
        this(name, namespace, status, ip, node, restarts, age, logs, yaml, containers, null);
    }

    public PodViewModel(String name, String namespace, String status, String ip, String node, int restarts,
            String age, String logs, String yaml, java.util.List<String> containers, java.util.Map<String, Integer> containerRestarts) {
        this.name = name;
        this.namespace = namespace;
        this.status = status;
        this.ip = ip;
        this.node = node;
        this.restarts = restarts;
        this.age = age;
        this.logs = logs;
        this.yaml = yaml;
        this.containers = containers;
        this.containerRestarts = containerRestarts;
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

    public String getIp() {
        return ip;
    }

    public String getNode() {
        return node;
    }

    public int getRestarts() {
        return restarts;
    }

    public String getAge() {
        return age;
    }

    public String getLogs() {
        return logs;
    }

    public String getYaml() {
        return yaml;
    }

    public java.util.List<String> getContainers() {
        return containers;
    }

    public java.util.Map<String, Integer> getContainerRestarts() {
        return containerRestarts;
    }
}
