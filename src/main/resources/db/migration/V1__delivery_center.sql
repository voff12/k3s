-- =====================================================================
-- 灵犀交付中心 V1 —— 12 张表
-- 依据 docs/plans/delivery-center-backend-design.md 第 3 节 DDL
-- 通用约定：id BIGINT AUTO_INCREMENT PK；created_at/updated_at；
--          枚举用 VARCHAR；软删除仅 application；utf8mb4
-- =====================================================================

-- 3.1 接入的应用
CREATE TABLE application (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(64)  NOT NULL COMMENT '应用名，如 订单服务',
  code          VARCHAR(64)  NOT NULL COMMENT '英文标识，如 order-service',
  team          VARCHAR(64)  COMMENT '所属团队，如 trade',
  repo_url      VARCHAR(512) NOT NULL COMMENT '代码仓库地址',
  repo_provider VARCHAR(16)  NOT NULL DEFAULT 'gitlab' COMMENT 'gitlab/github/other',
  default_branch VARCHAR(64) NOT NULL DEFAULT 'main',
  runtime_type  VARCHAR(16)  COMMENT 'java/python/docker 等，接入时自动识别',
  port          INT          COMMENT '应用监听端口',
  namespace     VARCHAR(64)  COMMENT '默认 K8s 命名空间',
  health_path   VARCHAR(256) COMMENT '健康检查路径',
  policy_template_id BIGINT  COMMENT '发布策略模板（低/标准/高风险）',
  prod_enabled  BOOLEAN NOT NULL DEFAULT FALSE COMMENT '生产环境是否已开放',
  status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code),
  KEY idx_team (team)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接入的应用';

-- 3.2 应用环境启用配置
CREATE TABLE app_environment (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_id      BIGINT NOT NULL,
  env         VARCHAR(16) NOT NULL COMMENT 'PREVIEW/BETA/PROD',
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  config_json TEXT COMMENT '环境级配置，如预览环境回收时长',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_app_env (app_id, env),
  CONSTRAINT fk_env_app FOREIGN KEY (app_id) REFERENCES application(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用环境启用配置';

-- 3.3 构建制品，digest 全局唯一
CREATE TABLE artifact (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_id        BIGINT NOT NULL,
  version       VARCHAR(64) NOT NULL COMMENT '版本号，如 v2.8.0',
  git_sha       VARCHAR(40) NOT NULL COMMENT '构建对应 commit',
  git_branch    VARCHAR(64),
  pr_number     INT         COMMENT '来源 PR',
  pr_title      VARCHAR(256),
  image_repo    VARCHAR(256) NOT NULL COMMENT 'Harbor 仓库地址',
  image_digest  VARCHAR(96) NOT NULL COMMENT 'sha256:...，跨环境复用的唯一凭证',
  scan_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PASSED/FAILED',
  scan_summary  TEXT COMMENT '漏洞摘要，如 critical:1 high:0',
  sbom_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/GENERATED',
  build_status  VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '构建状态',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_digest (image_digest),
  KEY idx_app_version (app_id, version),
  CONSTRAINT fk_art_app FOREIGN KEY (app_id) REFERENCES application(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='构建制品，digest 全局唯一';

-- 3.4 发布记录
CREATE TABLE delivery_release (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  release_no    VARCHAR(32) NOT NULL COMMENT '业务编号，如 rel-20261005-018',
  app_id        BIGINT NOT NULL,
  artifact_id   BIGINT NOT NULL COMMENT '发布的制品（生产复用已验证 digest）',
  env           VARCHAR(16) NOT NULL COMMENT 'PREVIEW/BETA/PROD',
  strategy      VARCHAR(16) NOT NULL COMMENT 'CANARY/BLUE_GREEN/DEPLOY_ONLY',
  status        VARCHAR(16) NOT NULL COMMENT 'PENDING/RUNNING/AWAITING_APPROVAL/PAUSED/SUCCESS/FAILED/ROLLED_BACK',
  current_stage VARCHAR(32) COMMENT '当前阶段代码，见 release_stage',
  current_traffic INT DEFAULT 0 COMMENT '灰度当前流量百分比',
  note          VARCHAR(512) COMMENT '发布说明',
  operator      VARCHAR(64) COMMENT '触发人',
  started_at    DATETIME(3),
  finished_at   DATETIME(3),
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_release_no (release_no),
  KEY idx_rel_app_status (app_id, status),
  KEY idx_rel_status (status),
  CONSTRAINT fk_rel_app FOREIGN KEY (app_id) REFERENCES application(id),
  CONSTRAINT fk_rel_art FOREIGN KEY (artifact_id) REFERENCES artifact(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布记录';

-- 3.5 发布阶段明细
CREATE TABLE release_stage (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  release_id  BIGINT NOT NULL,
  stage_order INT NOT NULL COMMENT '顺序 1..6',
  stage_code  VARCHAR(32) NOT NULL COMMENT 'PR_CHECK/BUILD/PREVIEW/BETA/CANARY/STABLE',
  stage_name  VARCHAR(64) NOT NULL COMMENT '展示名，如 生产灰度',
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/PASSED/FAILED/SKIPPED',
  started_at  DATETIME(3),
  finished_at DATETIME(3),
  detail_json TEXT COMMENT '阶段结果详情，如扫描报告链接',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_release_stage (release_id, stage_order),
  CONSTRAINT fk_stage_rel FOREIGN KEY (release_id) REFERENCES delivery_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布阶段明细';

-- 3.6 灰度扩流步骤
CREATE TABLE rollout_step (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  release_id  BIGINT NOT NULL,
  step_order  INT NOT NULL,
  traffic_pct INT NOT NULL COMMENT '目标流量百分比 5/20/50/100',
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/PASSED/PAUSED/ROLLED_BACK',
  pause_reason VARCHAR(256) COMMENT '暂停原因，如 P99 高于 stable 组 15%',
  started_at  DATETIME(3),
  finished_at DATETIME(3),
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_release_step (release_id, step_order),
  CONSTRAINT fk_roll_rel FOREIGN KEY (release_id) REFERENCES delivery_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='灰度扩流步骤';

-- 3.7 人工审批记录
CREATE TABLE approval (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  release_id  BIGINT NOT NULL,
  gate        VARCHAR(32) NOT NULL COMMENT 'BETA_ACCEPT/PROD_START/ROLLBACK',
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  required_role VARCHAR(32) COMMENT '如 发布负责人',
  approver    VARCHAR(64),
  comment     VARCHAR(512),
  decided_at  DATETIME(3),
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_release_status (release_id, status),
  CONSTRAINT fk_appr_rel FOREIGN KEY (release_id) REFERENCES delivery_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工审批记录';

-- 3.8 运行质量事件
CREATE TABLE quality_event (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_no    VARCHAR(32) NOT NULL COMMENT '业务编号',
  app_id      BIGINT NOT NULL,
  release_id  BIGINT COMMENT '关联发布，可为空（非发布引发）',
  severity    VARCHAR(8) NOT NULL COMMENT 'P1/P2/P3',
  type        VARCHAR(32) NOT NULL COMMENT 'SLO_BREACH/CANARY_REGRESSION/SCAN_BLOCK/ERROR_RATE',
  title       VARCHAR(256) NOT NULL COMMENT '如 登录接口错误率升高',
  detail      VARCHAR(1024),
  status      VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/OBSERVING/RESOLVED/MERGED',
  source      VARCHAR(16) NOT NULL DEFAULT 'RULE' COMMENT 'RULE/AI/MANUAL',
  detected_at DATETIME(3) NOT NULL,
  resolved_at DATETIME(3),
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_event_no (event_no),
  KEY idx_qe_app_status (app_id, status),
  KEY idx_qe_severity (severity),
  CONSTRAINT fk_qe_app FOREIGN KEY (app_id) REFERENCES application(id),
  CONSTRAINT fk_qe_rel FOREIGN KEY (release_id) REFERENCES delivery_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行质量事件';

-- 3.9 AI 诊断（只读推断，不触发动作）
CREATE TABLE ai_analysis (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id    BIGINT NOT NULL COMMENT '关联质量事件',
  conclusion  TEXT NOT NULL COMMENT '推断内容',
  confidence  INT COMMENT '置信度百分比 0-100',
  evidence_json TEXT COMMENT '证据列表：曲线/trace/PR 摘要',
  model       VARCHAR(64) COMMENT '模型标识',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_event (event_id),
  CONSTRAINT fk_ai_event FOREIGN KEY (event_id) REFERENCES quality_event(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 诊断（只读推断，不触发动作）';

-- 3.10 统一活动流水
CREATE TABLE activity_log (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_id      BIGINT COMMENT '可空：平台级活动',
  release_id  BIGINT COMMENT '可空',
  actor_type  VARCHAR(16) NOT NULL COMMENT 'USER/RULE/AI/SYSTEM',
  actor       VARCHAR(64) COMMENT '操作人/规则名',
  action      VARCHAR(64) NOT NULL COMMENT '如 PAUSE_ROLLOUT/GENERATE_ANALYSIS',
  message     VARCHAR(512) NOT NULL COMMENT '展示文案',
  env         VARCHAR(16) COMMENT 'CI/PREVIEW/BETA/PROD',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_app_time (app_id, created_at),
  KEY idx_release (release_id),
  CONSTRAINT fk_al_app FOREIGN KEY (app_id) REFERENCES application(id),
  CONSTRAINT fk_al_rel FOREIGN KEY (release_id) REFERENCES delivery_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一活动流水';

-- 3.11 发布策略模板
CREATE TABLE policy_template (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(32) NOT NULL COMMENT '低风险/标准/高风险',
  code          VARCHAR(16) NOT NULL,
  description   VARCHAR(256),
  config_json   TEXT NOT NULL COMMENT '灰度阶梯、自动暂停阈值、是否需人工批准等',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_pt_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布策略模板';

-- 3.11 集成连接
CREATE TABLE integration (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  type        VARCHAR(32) NOT NULL COMMENT 'GIT/HARBOR/PROMETHEUS/NOTIFY',
  name        VARCHAR(64) NOT NULL,
  endpoint    VARCHAR(256),
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'CONNECTED/PENDING/DISCONNECTED',
  secret_ref  VARCHAR(256) COMMENT '凭据引用（如 Nacos 配置 key），不存明文',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_type_name (type, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='集成连接';

-- 内置策略模板（低/标准/高风险，对应原型平台设置页）
INSERT INTO policy_template (name, code, description, config_json) VALUES
('低风险', 'LOW',    '自动开始灰度；规则通过后自动扩流', '{"autoCanary":true,"requireProdApproval":false,"canarySteps":[5,20,50,100],"autoRollback":true}'),
('标准',   'STANDARD','Beta 验收后灰度；异常自动暂停',   '{"autoCanary":false,"requireProdApproval":false,"canarySteps":[5,20,50,100],"autoRollback":true}'),
('高风险', 'HIGH',    '生产灰度需负责人批准；硬阈值可回退','{"autoCanary":false,"requireProdApproval":true,"canarySteps":[5,20,50,100],"autoRollback":true}');
