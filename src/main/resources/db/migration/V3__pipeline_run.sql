-- =====================================================================
-- V3 —— CI/CD 流水线运行记录持久化（DevOpsService 原内存 ConcurrentHashMap）
-- 目的：应用重启后流水线列表/状态/日志不丢失；KanikoPipelineTrigger 可跨重启轮询。
-- =====================================================================

CREATE TABLE pipeline_run (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id         VARCHAR(32)  NOT NULL COMMENT 'PipelineRun 内存 ID（8 位 UUID 前缀），与 SSE/页面 URL 一致',
  status         VARCHAR(16)  NOT NULL COMMENT 'PENDING/CLONING/MERGING/BUILDING/PUSHING/DEPLOYING/SUCCESS/FAILED',
  current_step   INT          NOT NULL DEFAULT -1 COMMENT '0-4 进行中，5 表示已结束',
  error_message  TEXT         COMMENT '失败原因',
  git_url        VARCHAR(512) NOT NULL,
  branch         VARCHAR(128) COMMENT '目标分支（合并部署时为基底分支）',
  image_name     VARCHAR(256) NOT NULL,
  image_tag      VARCHAR(64)  NOT NULL DEFAULT 'latest',
  config_json    TEXT         NOT NULL COMMENT 'PipelineConfig 全量序列化（含运行时/合并预览参数）',
  logs           MEDIUMTEXT   COMMENT '全量带时间戳日志行（JSON 数组），与内存 logs 一致',
  merge_commit_sha VARCHAR(40)  COMMENT '合并预览部署的合并 commit',
  conflict_files   TEXT         COMMENT '合并冲突文件列表（JSON 数组）',
  preview_namespace   VARCHAR(64)  COMMENT '预览命名空间 preview-<mergeSetId>',
  preview_nodeport_url VARCHAR(256) COMMENT '预览环境 NodePort 访问地址',
  started_at     DATETIME(3)  NOT NULL COMMENT '流水线创建时间（Asia/Shanghai）',
  finished_at    DATETIME(3),
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_run_id (run_id),
  KEY idx_started_at (started_at),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CI/CD 流水线运行记录（DevOps 页面数据源）';
