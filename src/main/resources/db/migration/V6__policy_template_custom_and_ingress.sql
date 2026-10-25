-- =====================================================================
-- V6 —— 发布策略模板自定义 + 审批门（设计 docs/plans/policy-template-and-ingress-design.md 3.4/3.8）
-- =====================================================================
ALTER TABLE policy_template
  ADD COLUMN builtin BOOLEAN NOT NULL DEFAULT FALSE COMMENT '内置模板不可删/不可改 code';
ALTER TABLE policy_template
  ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '软删除';

UPDATE policy_template SET builtin = TRUE WHERE code IN ('LOW', 'STANDARD', 'HIGH');

-- 审批门 requester 列（approval 表 V1 已建，补充发起人字段）
ALTER TABLE approval
  ADD COLUMN requester VARCHAR(64) NULL COMMENT '审批发起人（发布操作者）' AFTER status;
