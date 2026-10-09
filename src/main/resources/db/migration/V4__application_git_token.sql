-- =====================================================================
-- V4 —— application 增加 git_token 列
-- 目的：新建发布自动关联最新代码提交时，用各应用自配的 token 读私有仓库
--      （JGit ls-remote 拉分支头 SHA），替代手动填 40 位 Git SHA。
-- =====================================================================

ALTER TABLE application
  ADD COLUMN git_token VARCHAR(256) NULL COMMENT '私有仓库访问 token（各应用自配），查询接口不回显明文' AFTER repo_url;
