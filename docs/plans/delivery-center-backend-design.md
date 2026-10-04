# 灵犀交付中心 · 后端 API 与数据库设计

> 依据 `src/main/resources/static/delivery-prototype.html` 原型反推。
> 现有工程：Spring Boot 3.2 / Java 17，无数据库依赖，发布记录在内存（`ReleaseRecord`）。
> 本设计目标：让原型中的 6 个视图（交付总览 / 应用 / 发布 / 运行质量 / 集群运维 / 平台设置）+ 2 个向导（接入应用 / 新建发布）+ 发布详情抽屉全部有真实数据支撑。

## 目录

1. [总体架构](#1-总体架构)
2. [领域模型与 ER 关系](#2-领域模型与-er-关系)
3. [数据库设计（MySQL DDL）](#3-数据库设计mysql-ddl)
4. [API 设计](#4-api-设计)
5. [关键流程时序](#5-关键流程时序)
6. [与现有代码的衔接](#6-与现有代码的衔接)
7. [分期落地建议](#7-分期落地建议)

---

## 1. 总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│  前端（delivery-prototype.html → Thymeleaf/静态页 + fetch）        │
└──────────────┬──────────────────────────────────────────────────┘
               │ REST (JSON) + SSE（发布日志/进度推送）
┌──────────────▼──────────────────────────────────────────────────┐
│  Spring Boot 单体（现有 k3s-demo 工程扩展）                        │
│                                                                  │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌───────────────┐ │
│  │ 应用/接入   │ │ 发布流水    │ │ 运行质量    │ │ 平台配置       │ │
│  │ AppService │ │ ReleaseSvc │ │ QualitySvc │ │ SettingsSvc   │ │
│  └─────┬──────┘ └─────┬──────┘ └─────┬──────┘ └──────┬────────┘ │
│        │              │              │               │          │
│  ┌─────▼──────────────▼──────────────▼───────────────▼────────┐ │
│  │  Spring Data JPA（新增依赖）+ Flyway 迁移                    │ │
│  └─────┬──────────────────────────────────────────────────────┘ │
└────────┼─────────────────────────────────────────────────────────┘
         │
   ┌─────▼─────┐   ┌──────────────────────────────────────┐
   │  MySQL 8  │   │  外部系统（不落库，实时对接）             │
   │  业务数据   │   │  · K3s 集群（fabric8 client，已有）      │
   └───────────┘   │  · Git 平台（JGit，已有）                │
                   │  · Harbor（镜像/扫描/SBOM）              │
                   │  · Prometheus（指标、SLO、灰度对照）       │
                   │  · AI 服务（QwenService，已有）           │
                   └──────────────────────────────────────┘
```

**核心原则**

- **数据库只存"事实与决策"**：应用登记、发布记录、审批、事件、操作审计。集群实时状态（Pod/节点/资源水位）不落库，走 fabric8 实时查询——原型里"集群运维"页本来就是跳旧版页面的入口。
- **指标类数据（成功率曲线、P99 对照）不存明细**，只存 Prometheus 查询结果快照（事件关联用），曲线由后端代理 Prometheus 即时查询。
- **AI 分析结论落库但标记为"推断"**，与规则引擎结论（门禁动作）分开存储——对应原型抽屉里"AI 诊断"与"操作记录"两个区块的分离。

## 2. 领域模型与 ER 关系

### 2.1 实体一览

| 实体 | 说明 | 对应原型位置 |
|---|---|---|
| `Application` | 接入的应用（服务） | 应用目录、"服务健康"列表 |
| `AppEnvironment` | 应用的环境启用配置（Preview/Beta/Prod） | 接入向导第 3 步 |
| `Artifact` | 构建制品（镜像 digest + 扫描/SBOM 状态） | 新建发布向导"可发布版本"、抽屉"制品 Digest" |
| `Release` | 一次发布运行（含目标环境、策略、当前阶段） | 发布列表、抽屉主体 |
| `ReleaseStage` | 发布的阶段明细（PR 检查→构建→Preview→Beta→灰度→稳定） | 抽屉"发布进度"步骤条 |
| `RolloutStep` | 灰度扩流步骤（5%→20%→50%→100%）及状态 | 抽屉"灰度健康 · 当前流量 20%" |
| `Approval` | 人工审批记录（Beta 验收、生产前确认） | "等待验收"、"需负责人确认" |
| `QualityEvent` | 运行质量事件（SLO 违规、灰度劣化、扫描阻断） | "需要我处理"、"活跃事件" |
| `AiAnalysis` | AI 诊断结论 + 证据引用（只读，不触发动作） | 抽屉"AI 诊断"、运行质量"AI 分析记录" |
| `ActivityLog` | 统一操作/事件流水（人工+自动+规则） | "最近活动"、抽屉"操作记录" |
| `PolicyTemplate` | 发布策略模板（低/标准/高风险） | 平台设置"发布策略模板" |
| `Integration` | 集成连接（Git/Harbor/Prometheus/通知） | 平台设置"集成连接" |

### 2.2 ER 关系

```
Application 1 ──── n AppEnvironment        （一个应用多环境启用配置）
Application 1 ──── n Artifact              （一个应用多制品）
Application 1 ──── n Release               （一个应用多次发布）
Application 1 ──── 1 PolicyTemplate(引用)   （应用选择策略模板）
Release     1 ──── n ReleaseStage          （发布含多阶段，有序）
Release     1 ──── n RolloutStep           （仅灰度发布有，有序）
Release     1 ──── n Approval              （验收/审批节点）
Release     1 ──── n QualityEvent          （发布引发/关联的事件）
QualityEvent 1 ─── n AiAnalysis            （一个事件多轮 AI 分析）
Release     1 ──── n ActivityLog           （发布相关流水）
Application 1 ──── n ActivityLog           （应用级流水，如接入、配置变更）
```

关键设计点：

- **Artifact 独立成表**：原型强调"生产发布复用已验证的 digest，不重新构建"，所以制品（含 `sha256`、扫描结论、SBOM 状态）必须与发布解耦，一次构建多环境复用。
- **Release 与 Stage 分表**：抽屉里的 6 步进度条、发布列表的"阶段"列，都来自 `ReleaseStage`，而不是把阶段塞进一个 JSON 字段——阶段需要单独的状态机和时间戳。
- **QualityEvent 是"需要我处理"的唯一来源**：总览页的待办、发布页的阻断标记、运行质量页的活跃事件，全部由同一张表按 `severity/status` 过滤得出，避免三处各存一份。

## 3. 数据库设计（MySQL DDL）

> 通用约定：所有表含 `id BIGINT AUTO_INCREMENT PK`、`created_at`、`updated_at`；枚举用 `VARCHAR` 存（可读性优先，配合 CHECK 约束）；软删除仅用于 `application`。字符集 `utf8mb4`。

### 3.1 application（应用）

```sql
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
```

### 3.2 app_environment（应用环境启用）

```sql
CREATE TABLE app_environment (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_id      BIGINT NOT NULL,
  env         VARCHAR(16) NOT NULL COMMENT 'PREVIEW/BETA/PROD',
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  config_json JSON COMMENT '环境级配置，如预览环境回收时长',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_app_env (app_id, env),
  CONSTRAINT fk_env_app FOREIGN KEY (app_id) REFERENCES application(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用环境启用配置';
```

### 3.3 artifact（构建制品）

```sql
CREATE TABLE artifact (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_id        BIGINT NOT NULL,
  version       VARCHAR(64) NOT NULL COMMENT '版本号，如 v2.8.0',
  git_sha       CHAR(40)    NOT NULL COMMENT '构建对应 commit',
  git_branch    VARCHAR(64),
  pr_number     INT         COMMENT '来源 PR',
  pr_title      VARCHAR(256),
  image_repo    VARCHAR(256) NOT NULL COMMENT 'Harbor 仓库地址',
  image_digest  VARCHAR(96) NOT NULL COMMENT 'sha256:...，跨环境复用的唯一凭证',
  scan_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PASSED/FAILED',
  scan_summary  JSON COMMENT '漏洞摘要，如 critical:1 high:0',
  sbom_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/GENERATED',
  build_status  VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '构建状态',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_digest (image_digest),
  KEY idx_app_version (app_id, version),
  CONSTRAINT fk_art_app FOREIGN KEY (app_id) REFERENCES application(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='构建制品，digest 全局唯一';
```

### 3.4 release（发布）

```sql
CREATE TABLE release (
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
  KEY idx_app_status (app_id, status),
  KEY idx_status (status),
  CONSTRAINT fk_rel_app FOREIGN KEY (app_id) REFERENCES application(id),
  CONSTRAINT fk_rel_art FOREIGN KEY (artifact_id) REFERENCES artifact(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布记录';
```

### 3.5 release_stage（发布阶段）

```sql
CREATE TABLE release_stage (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  release_id  BIGINT NOT NULL,
  stage_order INT NOT NULL COMMENT '顺序 1..6',
  stage_code  VARCHAR(32) NOT NULL COMMENT 'PR_CHECK/BUILD/PREVIEW/BETA/CANARY/STABLE',
  stage_name  VARCHAR(64) NOT NULL COMMENT '展示名，如 生产灰度',
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/PASSED/FAILED/SKIPPED',
  started_at  DATETIME(3),
  finished_at DATETIME(3),
  detail_json JSON COMMENT '阶段结果详情，如扫描报告链接',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_release_stage (release_id, stage_order),
  CONSTRAINT fk_stage_rel FOREIGN KEY (release_id) REFERENCES release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布阶段明细';
```

### 3.6 rollout_step（灰度步骤）

```sql
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
  CONSTRAINT fk_roll_rel FOREIGN KEY (release_id) REFERENCES release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='灰度扩流步骤';
```

### 3.7 approval（审批）

```sql
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
  CONSTRAINT fk_appr_rel FOREIGN KEY (release_id) REFERENCES release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工审批记录';
```

### 3.8 quality_event（质量事件）

```sql
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
  KEY idx_app_status (app_id, status),
  KEY idx_severity (severity),
  CONSTRAINT fk_qe_app FOREIGN KEY (app_id) REFERENCES application(id),
  CONSTRAINT fk_qe_rel FOREIGN KEY (release_id) REFERENCES release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行质量事件';
```

### 3.9 ai_analysis（AI 分析）

```sql
CREATE TABLE ai_analysis (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id    BIGINT NOT NULL COMMENT '关联质量事件',
  conclusion  TEXT NOT NULL COMMENT '推断内容',
  confidence  INT COMMENT '置信度百分比 0-100',
  evidence_json JSON COMMENT '证据列表：曲线/trace/PR 摘要',
  model       VARCHAR(64) COMMENT '模型标识',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_event (event_id),
  CONSTRAINT fk_ai_event FOREIGN KEY (event_id) REFERENCES quality_event(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 诊断（只读推断，不触发动作）';
```

### 3.10 activity_log（活动流水）

```sql
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
  CONSTRAINT fk_al_rel FOREIGN KEY (release_id) REFERENCES release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一活动流水';
```

### 3.11 policy_template（策略模板）与 integration（集成连接）

```sql
CREATE TABLE policy_template (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(32) NOT NULL COMMENT '低风险/标准/高风险',
  code          VARCHAR(16) NOT NULL,
  description   VARCHAR(256),
  config_json   JSON NOT NULL COMMENT '灰度阶梯、自动暂停阈值、是否需人工批准等',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布策略模板';

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
```


## 4. API 设计

> 统一前缀 `/api/delivery`。响应统一包裹 `{ code, message, data }`；分页参数 `page/size`，返回 `{ total, items }`。错误码沿用 HTTP 状态 + 业务码。

### 4.1 交付总览（Overview）

| 方法 | 路径 | 说明 | 数据来源 |
|---|---|---|---|
| GET | `/api/delivery/overview` | 总览聚合接口：待处理数、进行中发布数、健康服务数、今日成功发布数 | `quality_event` + `release` + `application` 聚合 |
| GET | `/api/delivery/overview/attention` | "需要我处理"列表（按严重度排序） | `quality_event` WHERE status='OPEN' |
| GET | `/api/delivery/overview/ongoing-releases` | 正在进行的发布（灰度中/Beta 验收） | `release` WHERE status IN ('RUNNING','WAITING_APPROVAL') |
| GET | `/api/delivery/overview/health` | 服务健康列表（成功率 + 状态 pill） | Prometheus 实时查询 + `application` |
| GET | `/api/delivery/overview/success-rate?window=24h` | 成功率趋势曲线（当前版本 vs SLO 目标线） | Prometheus range query 代理 |
| GET | `/api/delivery/overview/activities?limit=20` | 最近活动流水 | `activity_log` |

### 4.2 应用（Applications）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/delivery/applications` | 应用目录（支持 `keyword/team/healthStatus` 过滤），含生产版本、发布状态、SLO 状态、待处理数 |
| GET | `/api/delivery/applications/{id}` | 应用详情 |
| POST | `/api/delivery/applications` | 创建应用（接入向导最终提交） |
| PUT | `/api/delivery/applications/{id}` | 更新应用配置 |
| DELETE | `/api/delivery/applications/{id}` | 停用应用（软删除） |
| POST | `/api/delivery/applications/detect-runtime` | 接入向导第 2 步：传入 repoUrl，返回运行时识别结果（Dockerfile/Maven/Python） |
| GET | `/api/delivery/applications/{id}/artifacts` | 应用的可发布制品列表（新建发布向导第 1 步） |
| GET | `/api/delivery/applications/{id}/releases` | 应用的发布历史 |

### 4.3 发布（Releases）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/delivery/releases` | 发布列表（`env/status/keyword` 过滤），含阶段、状态、环境、更新时间 |
| GET | `/api/delivery/releases/{id}` | 发布详情（抽屉数据：进度、变更与制品、灰度健康、AI 诊断、操作记录） |
| POST | `/api/delivery/releases` | 创建发布（新建发布向导提交：appId + artifactId + targetEnv + strategy + 门禁选项） |
| POST | `/api/delivery/releases/{id}/approve` | 审批（Beta 验收确认 / 生产前确认），body: `{ action: APPROVE|REJECT, comment }` |
| POST | `/api/delivery/releases/{id}/pause` | 暂停扩流 |
| POST | `/api/delivery/releases/{id}/resume` | 继续扩流 |
| POST | `/api/delivery/releases/{id}/rollback` | 回退版本（写审计，触发 K8s rollout undo） |
| POST | `/api/delivery/releases/{id}/promote` | 手动推进到下一灰度步（跳过自动条件时需审批） |
| GET | `/api/delivery/releases/{id}/logs?fromIndex=0` | 构建日志增量拉取（兼容现有 SSE：`/release/logs/stream/{id}`） |
| GET | `/api/delivery/releases/{id}/metrics-compare` | 灰度对照指标（5xx 率、P99、样本数，canary vs stable） | Prometheus |

### 4.4 运行质量（Quality）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/delivery/quality/summary` | 质量页统计：SLO 达标数、活跃事件数、错误预算、平均定位时间 |
| GET | `/api/delivery/quality/events` | 活跃事件列表（`severity/status` 过滤） |
| GET | `/api/delivery/quality/events/{id}` | 事件详情（含关联发布、AI 分析记录） |
| POST | `/api/delivery/quality/events/{id}/acknowledge` | 确认/认领事件 |
| POST | `/api/delivery/quality/events/{id}/resolve` | 解决事件 |
| GET | `/api/delivery/quality/slo` | 服务 SLO 列表（30 天滚动窗口达标情况） |
| GET | `/api/delivery/quality/ai-analyses?eventId=` | AI 分析记录列表 |

### 4.5 集群运维（Cluster）— 薄代理

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/delivery/cluster/summary` | 节点/工作负载/资源水位三卡片（fabric8 实时查询，不落库） |
| GET | `/api/delivery/cluster/events` | 最近集群事件（K8s Events，标注关联应用） |

> Pod/存储/内存管理沿用现有 `/pods`、`/memory`、`/store` 页面，交付中心只做入口跳转。

### 4.6 平台设置（Settings）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST/PUT/DELETE | `/api/delivery/settings/integrations` | 集成连接 CRUD（凭据加密存储，列表不回显明文） |
| POST | `/api/delivery/settings/integrations/{id}/test` | 连接测试 |
| GET | `/api/delivery/settings/policy-templates` | 策略模板列表 |
| PUT | `/api/delivery/settings/policy-templates/{id}` | 更新策略模板（灰度步长、门禁规则、自动回退阈值） |

### 4.7 实时推送（SSE）

| 路径 | 说明 |
|---|---|
| `GET /api/delivery/stream/releases/{id}` | 单发布进度 + 日志流（复用现有 SseEmitter 模式） |
| `GET /api/delivery/stream/overview` | 总览看板推送：发布状态变更、新事件、健康度变化（30s 心跳） |

### 4.8 请求/响应示例

**创建发布** `POST /api/delivery/releases`

```json
{
  "applicationId": 1,
  "artifactId": 42,
  "targetEnv": "PRODUCTION",
  "strategy": "CANARY",
  "note": "优化库存扣减并减少数据库锁等待。",
  "requireApproval": true
}
```

响应：

```json
{
  "code": 0,
  "data": {
    "id": 1018,
    "releaseNo": "rel-20261005-018",
    "status": "PENDING_APPROVAL",
    "stages": [
      {"name": "PR_CHECK", "status": "PASSED"},
      {"name": "BUILD", "status": "SKIPPED", "reason": "复用已验证制品"},
      {"name": "PREVIEW", "status": "SKIPPED"},
      {"name": "BETA", "status": "PASSED"},
      {"name": "PROD_CANARY", "status": "PENDING"},
      {"name": "STABLE", "status": "PENDING"}
    ]
  }
}
```

**发布详情**（抽屉）`GET /api/delivery/releases/1018`

```json
{
  "code": 0,
  "data": {
    "id": 1018,
    "releaseNo": "rel-20261005-018",
    "application": {"id": 1, "name": "订单服务", "code": "order-service"},
    "version": "v2.8.0",
    "commitSha": "4c18fa2",
    "prNumber": 479,
    "prTitle": "优化库存扣减",
    "artifact": {
      "digest": "sha256:94e7...cb12",
      "scanStatus": "PASSED",
      "sbomStatus": "GENERATED"
    },
    "targetEnv": "PRODUCTION",
    "strategy": "CANARY",
    "currentStage": "PROD_CANARY",
    "stages": ["..."],
    "rollout": {
      "currentStep": {"percent": 20, "status": "HOLD", "startedAt": "..."},
      "steps": [
        {"percent": 5,  "status": "PASSED"},
        {"percent": 20, "status": "HOLD"},
        {"percent": 50, "status": "PENDING"},
        {"percent": 100, "status": "PENDING"}
      ],
      "metrics": {
        "errorRate": {"canary": 0.0008, "stable": 0.0006, "unit": "ratio"},
        "p99LatencyMs": {"canary": 354, "stable": 300, "deltaPercent": 18},
        "sampleCount": 18420
      },
      "holdReason": "P99 高于 stable 组 15% 阈值"
    },
    "aiAnalysis": {
      "conclusion": "新版本的 P99 延迟较稳定版本高 18%...",
      "confidence": 0.76,
      "evidences": ["P99 对照曲线", "6 条代表性 Trace", "PR #479 变更摘要"],
      "ruleConclusion": "暂停扩流",
      "note": "AI 建议仅供排查，不触发回退"
    },
    "operations": [
      {"time": "09:53", "actor": "RULE_ENGINE", "action": "HOLD_ROLLOUT", "detail": "P99 高于 stable 组 15% 阈值"},
      {"time": "09:54", "actor": "AI", "action": "ANALYSIS_GENERATED", "detail": "引用了 4 项监控证据"}
    ]
  }
}
```

## 5. 关键流程时序

### 5.1 接入应用（向导 4 步）

```
前端                          后端
 │ POST /applications/detect-runtime  │  ← 向导第 2 步（JGit 读仓库结构）
 │◄── {runtimeType, buildFile, port}  │
 │ POST /applications                 │  ← 向导第 4 步确认
 │    {name, repoUrl, branch, runtime,│
 │     port, healthPath, envs, policy}│
 │◄── {id, code}                      │  → 写 application + app_environment
 │                                    │  → activity_log: APP_ONBOARDED
```

### 5.2 新建发布（向导 3 步 → 执行）

```
前端                          后端
 │ GET /applications/{id}/artifacts   │  ← 向导第 1 步：可发布版本列表
 │    ?status=SCAN_PASSED             │    （只列扫描通过的制品）
 │ POST /releases                     │  ← 向导第 3 步确认
 │    {applicationId, artifactId,     │
 │     targetEnv, rolloutType,        │
 │     note, requireApproval}         │
 │◄── {id, status: PENDING}           │  → 写 release + release_stage(6 行)
 │                                    │  → 若 targetEnv=PROD 且策略要求审批：
 │                                    │    建 approval(PENDING)，状态 WAITING_APPROVAL
 │                                    │  → 异步执行流水线（复用现有 ReleaseService）
 │ GET  /releases/{id}                │  ← 抽屉轮询 / SSE 推送
 │ GET  /releases/{id}/logs?from=0    │  ← 日志增量（现有 SSE 机制）
```

### 5.3 灰度推进与自动暂停/回退

```
规则引擎（定时/指标回调）
 │ 查询 Prometheus：canary 组 vs stable 组 P99/5xx
 │ 达到扩流条件 → rollout_step: PENDING→ACTIVE，更新流量比例
 │ 触发暂停阈值 → rollout_step: PAUSED
 │   → quality_event(OPEN, severity=AMBER, type=CANARY_DEGRADED)
 │   → activity_log(自动, RULE_ENGINE)
 │   → 触发 AI 分析（异步）：写 ai_analysis（只读，不动状态）
 │ 触发回退阈值 → release: ROLLING_BACK → ROLLED_BACK
 │   → activity_log + 通知渠道
人工操作（抽屉按钮）
 │ POST /releases/{id}/rollout/advance   继续扩流
 │ POST /releases/{id}/rollout/pause     暂停扩流
 │ POST /releases/{id}/rollback          回退版本（需 approval 或直接执行，按策略）
```

### 5.4 Beta 验收 / 生产前审批

```
 │ POST /releases/{id}/approvals/{approvalId}/approve   {comment}
 │ POST /releases/{id}/approvals/{approvalId}/reject    {comment}
 │ → 通过后 release 从 WAITING_APPROVAL → RUNNING，继续下一阶段
 │ → 拒绝则 release: BLOCKED，产生 quality_event
```

## 6. 与现有代码的衔接

现有工程已有可复用的部分，新设计不推翻它们：

| 现有代码 | 复用方式 |
|---|---|
| `ReleaseService` / `ReleaseRecord`（内存态发布执行） | 保留为**执行引擎**；新增的 `Release`/`ReleaseStage` 表是它的**持久化外壳**——发布创建时写库，执行过程中按阶段推进更新库，执行完把日志摘要、制品 digest 回写。内存对象仍是运行时热数据，库是审计与恢复源。 |
| `ReleaseController`（`/release/run` 等） | 保留旧接口兼容旧页面；新页面走 `/api/delivery/**`。两者共用 `ReleaseService`。 |
| `QwenService`（AI） | `AiAnalysis` 表的写入方。AI 结论只写库、不直接触发任何发布动作（对应原型"AI 建议仅供排查，不触发回退"）。 |
| fabric8 client（K3s 查询） | 集群运维页实时数据源，不落库。 |
| JGit | 接入向导 `detect-runtime` 的实现基础（已有 ls-remote 能力，扩展为读仓库文件树）。 |
| Nacos 配置 | `Integration` 表存连接元数据，**密钥类凭据仍走 Nacos/环境变量**，库里只存引用名，不存明文。 |

**需要新增的依赖**：`spring-boot-starter-data-jpa`、`mysql-connector-j`、`flyway-core`（+ flyway-mysql）。

**需要新增的包结构**：

```
com.example.k3sdemo.delivery
├── controller/   DeliveryOverviewController, ApplicationController,
│                 ReleaseController(v2), QualityController, SettingsController
├── service/      对应 Service
├── repository/   Spring Data JPA 接口
├── entity/       与第 3 节表一一对应
└── dto/          请求/响应对象
```

## 7. 分期落地建议

| 阶段 | 范围 | 验收标准 |
|---|---|---|
| **P1 数据层打底** | 引入 JPA/MySQL/Flyway；建 `application`、`app_environment`、`policy_template`、`integration` 四表；接入向导（应用创建）走通并落库 | 向导 4 步完成后刷新页面，应用出现在目录里 |
| **P2 发布持久化** | 建 `artifact`、`release`、`release_stage`、`activity_log`；`ReleaseService` 执行过程回写库；发布列表/详情抽屉读库 | 发布一次后，重启应用，发布记录与阶段进度仍在 |
| **P3 审批与灰度** | 建 `approval`、`rollout_step`；审批接口、灰度步骤推进、规则暂停/回退动作落库 | Beta 验收通过后灰度自动推进；暂停/回退在抽屉操作记录可见 |
| **P4 运行质量** | 建 `quality_event`、`ai_analysis`；Prometheus 代理接口；总览页聚合接口 | 总览"需要我处理"与运行质量"活跃事件"来自同一数据源 |
| **P5 前端接线** | `delivery-prototype.html` 去掉演示数据，全部改 fetch 调用 | 原型所有 toast("尚未接入") 的按钮都有真实行为 |

**建议先做 P1+P2**：这两期完成后，"应用 + 发布"主链路（原型最核心的两个视图）就有真实数据了，P3~P5 可以按需排期。

**遗留决策点**（需要你确认）：

1. **数据库选型**：设计按 MySQL 8 写的 DDL。如果团队更倾向 PostgreSQL 或复用现有库，DDL 需要小改（`DATETIME(3)`→`TIMESTAMP`、`COMMENT` 语法等）。
2. **用户/权限**：原型里有"发布负责人""吴工"等角色，但本设计**未包含用户体系**（假设沿用现有登录或先不做权限）。若要做审批人指定、按角色控制生产发布权限，需要加 `user`/`role` 表和审批人路由规则——这会显著扩大 P3 范围。
3. **指标存储**：成功率曲线、P99 对照依赖 Prometheus 已在采集业务指标。如果目标应用还没接 Prometheus，P4 的"服务健康"列会没有数据来源，需要先定指标采集方案。
