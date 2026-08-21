-- ========================================================================
-- business.identity：账号档案（A2 §3 最小版，票 #19）
-- idn_accounts：首次登录按外部 ID（OIDC sub）自动建档，无角色概念（角色票再加）；
-- 无 issuer 列（v1 单 IdP，多 IdP 进雾）。唯一索引 = 「同 sub 不重复建档」最终防线，
-- 常态路径靠 callback upsert 编排。不软删除（审计字段照常保留）。
-- ========================================================================

CREATE TABLE idn_accounts (
    id           BIGINT PRIMARY KEY,     -- TSID（建档时应用侧生成）
    external_id  VARCHAR(100) NOT NULL,  -- identity 侧 sub（稳定不变）
    display_name VARCHAR(200) NOT NULL,  -- 显示名（nickname→name→preferred_username→sub 推导）
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   BIGINT,
    updated_by   BIGINT
);

CREATE UNIQUE INDEX uk_idn_accounts_external_id ON idn_accounts (external_id);
