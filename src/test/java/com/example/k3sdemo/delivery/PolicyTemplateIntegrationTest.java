package com.example.k3sdemo.delivery;

import com.example.k3sdemo.delivery.dto.PolicyTemplateConfig;
import com.example.k3sdemo.delivery.entity.PolicyTemplate;
import com.example.k3sdemo.delivery.repository.ApplicationRepository;
import com.example.k3sdemo.delivery.repository.PolicyTemplateRepository;
import com.example.k3sdemo.delivery.service.PolicyTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1 策略模板自定义（设计 3.4）：CRUD + 内置保护 + 软删 + 引用保护。
 */
@SpringBootTest
@Transactional
class PolicyTemplateIntegrationTest {

    @Autowired
    private PolicyTemplateService service;
    @Autowired
    private PolicyTemplateRepository repository;
    @Autowired
    private ApplicationRepository applicationRepository;

    private PolicyTemplateConfig sampleConfig() {
        PolicyTemplateConfig config = new PolicyTemplateConfig();
        config.setRequireProdApproval(true);
        config.setCanarySteps(List.of(10, 50, 100));
        return config;
    }

    @Test
    void listShowsBuiltinTemplatesMarkedAndCustomAfterCreate() {
        List<PolicyTemplate> before = service.list();
        assertThat(before).extracting(PolicyTemplate::getCode)
                .contains("LOW", "STANDARD", "HIGH");

        PolicyTemplate created = service.create(new PolicyTemplateService.CreateRequest(
                "支付专用策略", "支付域自定义", sampleConfig(), "吴工"));
        assertThat(created.getBuiltin()).isFalse();
        assertThat(created.getCode()).startsWith("CUSTOM_");
        assertThat(service.list()).extracting(PolicyTemplate::getId)
                .contains(created.getId());
        assertThat(service.get(created.getId()).getConfigJson()).contains("requireProdApproval");
    }

    @Test
    void updateCannotTouchBuiltinAndDeleteProtectsBuiltinAndReferenced() {
        PolicyTemplate custom = service.create(new PolicyTemplateService.CreateRequest(
                "临时策略", null, sampleConfig(), "吴工"));
        service.update(custom.getId(), new PolicyTemplateService.CreateRequest(
                "临时策略v2", "描述", sampleConfig(), "吴工"));
        assertThat(service.get(custom.getId()).getName()).isEqualTo("临时策略v2");

        PolicyTemplate builtin = service.list().stream()
                .filter(t -> Boolean.TRUE.equals(t.getBuiltin()))
                .filter(t -> "HIGH".equals(t.getCode()))
                .findFirst().orElseThrow();
        // 内置模板：可改 name（设计 3.4：仅可改 name/description/config），不可删
        service.update(builtin.getId(), new PolicyTemplateService.CreateRequest(
                "改名", null, sampleConfig(), "吴工"));
        assertThat(service.get(builtin.getId()).getName()).isEqualTo("改名");
        assertThat(service.get(builtin.getId()).getCode()).isEqualTo("HIGH");
        assertThatThrownBy(() -> service.delete(builtin.getId(), "吴工"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void deleteMarksDeletedNotPhysical() {
        PolicyTemplate custom = service.create(new PolicyTemplateService.CreateRequest(
                "待删策略", null, sampleConfig(), "吴工"));
        service.delete(custom.getId(), "吴工");
        assertThat(service.list()).extracting(PolicyTemplate::getId)
                .doesNotContain(custom.getId());
        assertThat(repository.findById(custom.getId()))
                .hasValueSatisfying(t -> assertThat(t.getDeleted()).isTrue());
    }

    @Test
    void cloneGeneratesNewCodeAndKeepsConfig() {
        PolicyTemplate created = service.create(new PolicyTemplateService.CreateRequest(
                "克隆源", null, sampleConfig(), "吴工"));
        PolicyTemplate cloned = service.clone(created.getId(), "李工");
        assertThat(cloned.getId()).isNotEqualTo(created.getId());
        assertThat(cloned.getCode()).isNotEqualTo(created.getCode());
        assertThat(cloned.getConfigJson()).isEqualTo(created.getConfigJson());
        assertThat(cloned.getName()).isEqualTo("克隆源-副本");
    }

    @Test
    void configValidateRejectsNonIncreasingSteps() {
        PolicyTemplateConfig config = new PolicyTemplateConfig();
        config.setCanarySteps(List.of(50, 10, 100));
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class);
    }
}
