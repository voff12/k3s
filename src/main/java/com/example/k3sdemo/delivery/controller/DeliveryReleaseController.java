package com.example.k3sdemo.delivery.controller;

import com.example.k3sdemo.delivery.dto.ApiResponse;
import com.example.k3sdemo.delivery.dto.CreateReleaseRequest;
import com.example.k3sdemo.delivery.entity.Release;
import com.example.k3sdemo.delivery.entity.ReleaseStage;
import com.example.k3sdemo.delivery.service.ReleaseExecutionService;
import com.example.k3sdemo.delivery.service.ReleasePersistenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 发布查询与创建（设计 4.3，P2 范围：列表/详情/创建）。
 * 执行：POST /{id}/execute 接现有 BuildKit 流水线（P3 桥接）。
 */
@RestController
@RequestMapping("/api/delivery/releases")
public class DeliveryReleaseController {

    private final ReleasePersistenceService persistenceService;
    private final ReleaseExecutionService executionService;

    public DeliveryReleaseController(ReleasePersistenceService persistenceService,
                                     ReleaseExecutionService executionService) {
        this.persistenceService = persistenceService;
        this.executionService = executionService;
    }

    @GetMapping
    public ApiResponse<List<Release>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(persistenceService.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        Release release = persistenceService.get(id);
        List<ReleaseStage> stages = persistenceService.stages(id);
        return ApiResponse.ok(Map.of(
                "release", release,
                "stages", stages
        ));
    }

    @PostMapping
    public ApiResponse<Release> create(@RequestBody CreateReleaseRequest req) {
        Release release = persistenceService.create(
                req.getApplicationId(), req.getArtifactId(),
                req.getTargetEnv(), req.getStrategy(),
                req.getNote(), req.getOperatorName());
        return ApiResponse.ok(release);
    }

    /**
     * 触发执行：接通现有 BuildKit 流水线，异步推进 6 阶段。
     */
    @PostMapping("/{id}/execute")
    public ApiResponse<Release> execute(@PathVariable Long id) {
        return ApiResponse.ok(executionService.execute(id));
    }

    /**
     * 审批决策（设计 3.3）：通过后自动放行继续执行；驳回则发布终止。
     * body: { "gate": "PROD_START", "approve": true, "approver": "alice", "comment": "同意" }
     */
    @PostMapping("/{id}/approval")
    public ApiResponse<Release> decide(@PathVariable Long id, @RequestBody ApprovalDecisionRequest req) {
        return ApiResponse.ok(executionService.decide(
                id, req.getGate(), req.isApprove(), req.getApprover(), req.getComment()));
    }

    public static class ApprovalDecisionRequest {
        private String gate = "PROD_START";
        private boolean approve;
        private String approver;
        private String comment;

        public String getGate() { return gate; }
        public void setGate(String gate) { this.gate = gate; }
        public boolean isApprove() { return approve; }
        public void setApprove(boolean approve) { this.approve = approve; }
        public String getApprover() { return approver; }
        public void setApprover(String approver) { this.approver = approver; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }
}
