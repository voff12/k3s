-- 发布单号按日序号：rel-yyyyMMdd-NNN 的 NNN 从这里取
-- 取号用 INSERT ... ON DUPLICATE KEY UPDATE，行锁持有到事务提交，并发创建不会拿到同一个号
CREATE TABLE release_no_seq (
  seq_day  CHAR(8) NOT NULL PRIMARY KEY COMMENT '日期 yyyyMMdd',
  seq      INT     NOT NULL COMMENT '当日已发出的最大序号'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布单号当日序号';
