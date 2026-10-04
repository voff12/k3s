package com.example.k3sdemo.delivery.controller;

import com.example.k3sdemo.delivery.dto.ApiResponse;
import com.example.k3sdemo.delivery.dto.CreateApplicationRequest;
import com.example.k3sdemo.delivery.entity.Application;
import com.example.k3sdemo.delivery.service.ApplicationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 应用目录与接入（设计 4.2，P1 范围：列表/详情/创建/停用）。
 */
@RestController
@RequestMapping("/api/delivery/applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    public ApiResponse<List<Application>> list(
            @RequestParam(required = false) String team) {
        return ApiResponse.ok(applicationService.list(team));
    }

    @GetMapping("/{id}")
    public ApiResponse<Application> get(@PathVariable Long id) {
        return ApiResponse.ok(applicationService.get(id));
    }

    @PostMapping
    public ApiResponse<Application> create(@RequestBody CreateApplicationRequest req) {
        return ApiResponse.ok(applicationService.create(req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Application> disable(@PathVariable Long id) {
        return ApiResponse.ok(applicationService.disable(id));
    }
}
