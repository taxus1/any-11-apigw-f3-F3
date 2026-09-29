-- ============================================================================
-- 限流额度配置表（独立新表，不改 gw_app / gw_app_origin 的表结构）
--
-- 模型口径（务必和代码、README 一起看）：
-- 1. 一行 = 某个「范围」在固定时间窗内的调用次数上限（每分钟多少次）。
--      scope=APP ：某个应用整体的每分钟总量（全局所有来源、所有网关实例合计）；
--      scope=IP  ：某个应用下「单个来源地址」的每分钟额度，只卡刷凶的那个地址，
--                  不连累同应用的其他来源；
--      scope=DEFAULT：全局兜底行（app_no 固定为 '*'，不带 ip），没单独配的应用按它走。
--    两层额度彼此独立，可以同时配、只配一层、两层都不配。
-- 2. NULL 额度（per_minute_limit IS NULL）= 这一层明确「不限」；行不存在 = 没配，
--    按 DEFAULT → 不限 的顺序回落。把「没配」与「显式不限」分开，才说得清当前生效值
--    到底来自应用专属配置还是默认额度。
-- 3. 计数不落库：这张表只存「额度配置」（低频运营写）。转发链路上每个窗口的计数放在
--    Redis（所有网关实例共享的同一份），见 RateLimitWindowStore。窗口键自带 TTL，
--    过期由 Redis 自动回收，这张表与计数存储都不会无限膨胀。
-- 4. 热更新：额度改动走单行 upsert（事务提交后发变更事件），各实例的内存额度快照
--    事件即时刷新 + 定时兜底刷新；计数器 key 按窗口名划分，改额度不删计数器，
--    当前窗内立刻按新额度判定，下一个窗自然重新开始数。
-- 5. 不给 gw_app 加外键：应用不做物理删除（只有停用），额度行不会变孤儿；
--    给一个已停用/不存在的应用配额度也不报错（应用再启用时配置已就位）。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `gw_rate_limit` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（不作为业务编号）',
    `scope`            VARCHAR(16)  NOT NULL COMMENT '额度范围：APP=应用总量，IP=应用下来源地址，DEFAULT=全局默认',
    `app_no`           VARCHAR(64)  NOT NULL COMMENT '应用编号；DEFAULT 行固定为 *',
    `ip`               VARCHAR(64)  DEFAULT NULL COMMENT '来源地址（规范化 IPv4/IPv6 字面量）；仅 IP 行有值',
    `per_minute_limit` INT          DEFAULT NULL COMMENT '每分钟调用次数上限；NULL=这一层明确不限',
    `updated_by`       VARCHAR(128) DEFAULT NULL COMMENT '最后修改人',
    `created_at`       DATETIME(3)  NOT NULL COMMENT '创建时间',
    `updated_at`       DATETIME(3)  NOT NULL COMMENT '最后修改时间',
    PRIMARY KEY (`id`),
    -- 一个范围一行：upsert 靠它定位（APP/DEFAULT 行 ip 为 NULL，MySQL 唯一索引里 NULL 互不冲突，
    -- 但这两类行各自只会 upsert 一次，IP 行 (app_no, 规范化 ip) 天然唯一）
    UNIQUE KEY `uk_scope_app_ip` (`scope`, `app_no`, `ip`),
    -- 转发快照按应用装载、管理侧按应用列出/删除时走索引
    KEY `idx_app_no` (`app_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='限流额度配置（每分钟次数；计数在 Redis，不在本表）';
