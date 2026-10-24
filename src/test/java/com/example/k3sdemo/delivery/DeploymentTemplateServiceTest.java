package com.example.k3sdemo.delivery;

import com.example.k3sdemo.delivery.dto.DeploymentTemplate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentTemplateServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nacosDisabledByDefault() {
        assertThat(new DeploymentTemplate().getNacos().getEnabled()).isFalse();
    }

    @Test
    void environmentOverridesApplicationValues() {
        DeploymentTemplate base = new DeploymentTemplate();
        base.getEnv().put("PROFILE", "base");
        DeploymentTemplate override = new DeploymentTemplate();
        override.getEnv().put("PROFILE", "prod");
        com.example.k3sdemo.delivery.service.DeploymentTemplateService.merge(base, override);
        assertThat(base.getEnv()).containsEntry("PROFILE", "prod");
    }

    @Test
    void rejectsIncompleteSecretReference() {
        DeploymentTemplate template = new DeploymentTemplate();
        template.getSecretEnv().put("DB_PASSWORD", new DeploymentTemplate.SecretRef());
        assertThatThrownBy(template::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serializesReferenceNamesOnly() throws Exception {
        DeploymentTemplate template = new DeploymentTemplate();
        DeploymentTemplate.SecretRef ref = new DeploymentTemplate.SecretRef();
        ref.setName("app-db");
        ref.setKey("password");
        template.getSecretEnv().put("DB_PASSWORD", ref);
        String json = mapper.writeValueAsString(template);
        assertThat(json).contains("app-db").doesNotContain("secret-value");
    }
}
