-- 应用部署模板与环境差异；仅存非敏感值及 Secret 引用，不存凭据明文。
ALTER TABLE application ADD COLUMN deployment_template_json JSON NULL;
ALTER TABLE app_environment ADD COLUMN deployment_override_json JSON NULL;
