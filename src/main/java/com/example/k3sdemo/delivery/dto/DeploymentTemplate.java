package com.example.k3sdemo.delivery.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 应用/环境的结构化部署配置；敏感值只允许 Kubernetes Secret 引用。 */
public class DeploymentTemplate {

    private Boolean enabled = true;
    private NacosConfig nacos = new NacosConfig();
    private Map<String, String> env = new LinkedHashMap<>();
    private Map<String, SecretRef> secretEnv = new LinkedHashMap<>();
    private java.util.List<String> envFrom = new java.util.ArrayList<>();

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public NacosConfig getNacos() { return nacos; }
    public void setNacos(NacosConfig nacos) { this.nacos = nacos; }
    public java.util.List<String> getEnvFrom() { return envFrom; }
    public void setEnvFrom(java.util.List<String> envFrom) { this.envFrom = envFrom; }
    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env; }
    public Map<String, SecretRef> getSecretEnv() { return secretEnv; }
    public void setSecretEnv(Map<String, SecretRef> secretEnv) { this.secretEnv = secretEnv; }

    public static class NacosConfig {
        private Boolean enabled = false;
        private String serverAddr;
        private String namespace = "";
        private String group;
        private String dataId;
        private String authSecretRef = "nacos-auth";
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public String getServerAddr() { return serverAddr; }
        public void setServerAddr(String serverAddr) { this.serverAddr = serverAddr; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public String getGroup() { return group; }
        public void setGroup(String group) { this.group = group; }
        public String getDataId() { return dataId; }
        public void setDataId(String dataId) { this.dataId = dataId; }
        public String getAuthSecretRef() { return authSecretRef; }
        public void setAuthSecretRef(String authSecretRef) { this.authSecretRef = authSecretRef; }
    }

    public static class SecretRef {
        private String name;
        private String key;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
    }

    public void validate() {
        if (env != null) {
            env.forEach((name, value) -> validateEnvName(name));
        }
        if (secretEnv != null) {
            secretEnv.forEach((name, ref) -> {
                validateEnvName(name);
                if (ref == null || isBlank(ref.getName()) || isBlank(ref.getKey())) {
                    throw new IllegalArgumentException("secretEnv 需包含 Secret name 和 key");
                }
            });
        }
        if (envFrom != null) {
            envFrom.forEach(reference -> {
                if (reference == null || !reference.matches("(configMap|secret):[a-z0-9]([-a-z0-9.]*[a-z0-9])?")) {
                    throw new IllegalArgumentException("envFrom 格式应为 configMap:<name> 或 secret:<name>");
                }
            });
        }
        if (nacos != null && Boolean.TRUE.equals(nacos.getEnabled())) {
            if (isBlank(nacos.getServerAddr())) throw new IllegalArgumentException("Nacos serverAddr 不能为空");
            if (isBlank(nacos.getDataId())) throw new IllegalArgumentException("Nacos dataId 不能为空");
            if (isBlank(nacos.getAuthSecretRef())) throw new IllegalArgumentException("Nacos authSecretRef 不能为空");
        }
    }

    private static void validateEnvName(String name) {
        if (name == null || !Pattern.matches("[A-Za-z_][A-Za-z0-9_]*", name)) {
            throw new IllegalArgumentException("非法环境变量名: " + name);
        }
    }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
}
