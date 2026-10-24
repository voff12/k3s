package com.example.k3sdemo.service;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DevOpsDeploymentEnvironmentTest {

    @Test
    void addsMissingNacosConnectionVariables() {
        List<EnvVar> env = new ArrayList<>();
        DevOpsService.addEnvIfAbsent(env, "NACOS_ENABLED", "true");
        DevOpsService.addEnvIfAbsent(env, "NACOS_SERVER_ADDR", "nacos.default.svc.cluster.local:8848");
        DevOpsService.addSecretEnvIfAbsent(env, "NACOS_USERNAME", "username");

        assertThat(env).extracting(EnvVar::getName)
                .containsExactly("NACOS_ENABLED", "NACOS_SERVER_ADDR", "NACOS_USERNAME");
        assertThat(env.get(2).getValueFrom().getSecretKeyRef().getName()).isEqualTo("nacos-auth");
    }

    @Test
    void preservesExistingValuesAndSecretReferences() {
        List<EnvVar> env = new ArrayList<>();
        env.add(new EnvVar("NACOS_SERVER_ADDR", "custom-nacos:8848", null));
        env.add(new EnvVarBuilder().withName("NACOS_USERNAME")
                .withNewValueFrom().withNewSecretKeyRef("user", "custom-secret", false)
                .endValueFrom().build());
        DevOpsService.addEnvIfAbsent(env, "NACOS_SERVER_ADDR", "nacos.default.svc.cluster.local:8848");
        DevOpsService.addSecretEnvIfAbsent(env, "NACOS_USERNAME", "username");

        assertThat(env).hasSize(2);
        assertThat(env.get(0).getValue()).isEqualTo("custom-nacos:8848");
        assertThat(env.get(1).getValueFrom().getSecretKeyRef().getName()).isEqualTo("custom-secret");
    }

    @Test
    void patchesContainerWithoutOverridingDatabaseConfiguration() {
        var container = new io.fabric8.kubernetes.api.model.Container();
        container.setEnv(new ArrayList<>(List.of(new EnvVar("DB_URL", "jdbc:mysql://rds/k3db", null))));

        DevOpsService.ensureDeliveryEnvironment(container,
                "nacos.default.svc.cluster.local:8848", "", "DEFAULT_GROUP");

        assertThat(container.getEnv()).extracting(EnvVar::getName)
                .contains("DB_URL", "NACOS_ENABLED", "NACOS_SERVER_ADDR",
                        "NACOS_NAMESPACE", "NACOS_GROUP", "NACOS_USERNAME", "NACOS_PASSWORD");
        assertThat(container.getEnv().get(0).getValue()).isEqualTo("jdbc:mysql://rds/k3db");
        assertThat(container.getEnv()).filteredOn(e -> "NACOS_PASSWORD".equals(e.getName()))
                .singleElement().satisfies(e ->
                        assertThat(e.getValueFrom().getSecretKeyRef().getName()).isEqualTo("nacos-auth"));
    }

    @Test
    void templateRendersNacosAndSecretsWithoutOverridingExistingValues() {
        var template = new com.example.k3sdemo.delivery.dto.DeploymentTemplate();
        var nacos = template.getNacos();
        nacos.setEnabled(true);
        nacos.setServerAddr("nacos.default.svc.cluster.local:8848");
        nacos.setNamespace("");
        nacos.setGroup("DEFAULT_GROUP");
        nacos.setDataId("order-service.yaml");
        nacos.setAuthSecretRef("nacos-auth");
        template.getEnv().put("SPRING_PROFILES_ACTIVE", "prod");
        var ref = new com.example.k3sdemo.delivery.dto.DeploymentTemplate.SecretRef();
        ref.setName("order-db");
        ref.setKey("password");
        template.getSecretEnv().put("DB_PASSWORD", ref);
        template.getEnvFrom().add("configMap:order-settings");
        var container = new io.fabric8.kubernetes.api.model.Container();
        container.setEnv(new ArrayList<>(List.of(new EnvVar("SPRING_PROFILES_ACTIVE", "existing", null))));

        DevOpsService.applyDeploymentTemplate(container, template);

        assertThat(container.getEnv()).filteredOn(e -> "SPRING_PROFILES_ACTIVE".equals(e.getName()))
                .singleElement().extracting(EnvVar::getValue).isEqualTo("existing");
        assertThat(container.getEnv()).filteredOn(e -> "DB_PASSWORD".equals(e.getName()))
                .singleElement().satisfies(e -> {
                    assertThat(e.getValue()).isNull();
                    assertThat(e.getValueFrom().getSecretKeyRef().getName()).isEqualTo("order-db");
                    assertThat(e.getValueFrom().getSecretKeyRef().getKey()).isEqualTo("password");
                });
        assertThat(container.getEnv()).extracting(EnvVar::getName)
                .contains("NACOS_ENABLED", "NACOS_SERVER_ADDR", "NACOS_DATA_ID");
        assertThat(container.getEnvFrom()).singleElement()
                .extracting(e -> e.getConfigMapRef().getName()).isEqualTo("order-settings");
    }

    @Test
    void deliveryTriggerMarksDeploymentForRuntimeEnvironmentPatch() {
        var config = new com.example.k3sdemo.model.PipelineConfig();
        config.setDeliveryManaged(true);
        assertThat(config.isDeliveryManaged()).isTrue();
    }
}
