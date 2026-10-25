package com.example.k3sdemo.delivery.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 发布策略模板结构化配置（policy_template.config_json 的 DTO 映射）。
 * 兼容解析：缺字段取默认值，V1 种子的 JSON 可直接读入。
 */
public class PolicyTemplateConfig {

    /** 灰度流量阶梯，如 [5,20,50,100]；本期仅展示，不驱动切流 */
    private List<Integer> canarySteps = new ArrayList<>(List.of(5, 20, 50, 100));

    /** PROD 启动前需人工批准（发布停在 AWAITING_APPROVAL） */
    private boolean requireProdApproval = false;

    /** Beta 验收门（本期仅存配置，门控逻辑随真灰度二期） */
    private boolean requireBetaAcceptance = false;

    /** Beta 过后自动开始灰度 */
    private boolean autoCanary = false;

    /** 硬阈值触发自动回退 */
    private boolean autoRollback = true;

    public List<Integer> getCanarySteps() { return canarySteps; }
    public void setCanarySteps(List<Integer> canarySteps) { this.canarySteps = canarySteps; }
    public boolean isRequireProdApproval() { return requireProdApproval; }
    public void setRequireProdApproval(boolean requireProdApproval) { this.requireProdApproval = requireProdApproval; }
    public boolean isRequireBetaAcceptance() { return requireBetaAcceptance; }
    public void setRequireBetaAcceptance(boolean requireBetaAcceptance) { this.requireBetaAcceptance = requireBetaAcceptance; }
    public boolean isAutoCanary() { return autoCanary; }
    public void setAutoCanary(boolean autoCanary) { this.autoCanary = autoCanary; }
    public boolean isAutoRollback() { return autoRollback; }
    public void setAutoRollback(boolean autoRollback) { this.autoRollback = autoRollback; }

    /** 校验：1-6 步、每步 1-100、严格递增（末步应为 100 由服务层规范补齐，此处不强求） */
    public void validate() {
        if (canarySteps == null || canarySteps.isEmpty()) {
            throw new IllegalArgumentException("canarySteps 不能为空");
        }
        if (canarySteps.size() > 6) {
            throw new IllegalArgumentException("canarySteps 最多 6 步");
        }
        int prev = 0;
        for (Integer step : canarySteps) {
            if (step == null || step < 1 || step > 100) {
                throw new IllegalArgumentException("canarySteps 每步须在 1-100 之间: " + step);
            }
            if (step <= prev) {
                throw new IllegalArgumentException("canarySteps 须严格递增: " + canarySteps);
            }
            prev = step;
        }
    }
}
