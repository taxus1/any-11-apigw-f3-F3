-- H2（MODE=MySQL）下的第三方接入表，结构与 src/main/resources/db/gw_app.sql 对齐，
-- 用于应用/来路名单仓储的真实 SQL 集成测试，不依赖外部 MySQL。
CREATE TABLE IF NOT EXISTS gw_app (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    app_no            VARCHAR(64)  NOT NULL,
    app_name          VARCHAR(128) NOT NULL,
    secret_hash       VARCHAR(256) NOT NULL,
    secret_expires_at TIMESTAMP(3) DEFAULT NULL,
    enabled           TINYINT      NOT NULL DEFAULT 1,
    contact           VARCHAR(128) DEFAULT NULL,
    remark            VARCHAR(512) DEFAULT NULL,
    created_by        VARCHAR(128) DEFAULT NULL,
    created_at        TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_no (app_no)
);

CREATE TABLE IF NOT EXISTS gw_app_origin (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    app_no     VARCHAR(64) NOT NULL,
    ip         VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_ip (app_no, ip),
    KEY idx_app_no (app_no)
);

-- 限流额度配置（结构与 src/main/resources/db/gw_rate_limit.sql 对齐）
CREATE TABLE IF NOT EXISTS gw_rate_limit (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    scope            VARCHAR(16)  NOT NULL,
    app_no           VARCHAR(64)  NOT NULL,
    ip               VARCHAR(64)  DEFAULT NULL,
    per_minute_limit INT          DEFAULT NULL,
    updated_by       VARCHAR(128) DEFAULT NULL,
    created_at       TIMESTAMP(3) NOT NULL,
    updated_at       TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scope_app_ip (scope, app_no, ip),
    KEY idx_rl_app_no (app_no)
);
