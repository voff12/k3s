package com.example.k3sdemo.delivery.controller;

import com.example.k3sdemo.delivery.dto.ApiResponse;
import com.example.k3sdemo.delivery.entity.PolicyTemplate;
import com.example.k3sdemo.delivery.service.PolicyTemplateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发布策略模板管理（设计 3.2）：列表/创建/克隆/编辑/软删除。
 */
@RestController
@RequestMapping("/api/delivery/policy-templates")
public class PolicyTemplateController {

    private final PolicyTemplateService policyTemplateService;

    public PolicyTemplateController(PolicyTemplateService policyTemplateService) {
        this.policyTemplateService = policyTemplateService;
    }

    @GetMapping
    public ApiResponse<List<PolicyTemplate>> list() {
        return ApiResponse.ok(policyTemplateService.list());
    }

    @PostMapping
    public ApiResponse<PolicyTemplate> create(@RequestBody PolicyTemplateService.CreateRequest req) {
        return ApiResponse.ok(policyTemplateService.create(req));
    }

    @PostMapping("/{id}/clone")
    public ApiResponse<PolicyTemplate> clone(@PathVariable Long id,
                                             @RequestParam(defaultValue = "unknown") String operator) {
        return ApiResponse.ok(policyTemplateService.clone(id, operator));
    }

    @PutMapping("/{id}")
    public ApiResponse<PolicyTemplate> update(@PathVariable Long id,
                                              @RequestBody PolicyTemplateService.CreateRequest req) {
        return ApiResponse.ok(policyTemplateService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<PolicyTemplate> delete(@PathVariable Long id,
                                              @RequestParam(defaultValue = "unknown") String operator) {
        return ApiResponse.ok(policyTemplateService.delete(id, operator));
    }
}
