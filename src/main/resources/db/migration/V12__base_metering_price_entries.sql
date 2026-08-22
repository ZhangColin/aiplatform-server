-- ========================================================================
-- base.metering：单价表 met_price_entries（A6 §1，票 #29）——平台成本换算的
-- 单价数据，计量上下文私有表（不经端口暴露，业务层零感知）。
--
-- 匹配键 = (provider, model, token_kind)，与 UsageEvent 同键；改价 = 关旧行
-- （effective_to = now）+ 开新行（append 式不 UPDATE 单价），事件按 ts 落生效区间
-- 匹配，历史成本不漂移。生效区间不得重叠（维护纪律——唯一约束只防同起点，v1
-- 手工 SQL + 启动种子维护，重叠 = 换算重复计）。
--
-- token_kind 存 BaseEnum Integer code（#34 收敛房规）：1=input 2=output
-- 3=cache_read 4=cache_write 5=reasoning（spec §1 草案的小写字符串词表即此五档）。
--
-- unit_price 每 token 单价。精度 numeric(20,10) 偏离 spec §1 草案 numeric(12,8)：
-- 官方定价页现役最低价 deepseek-v4-flash 缓存命中 peak $0.014/1M = 1.4e-8/token，
-- 8 位小数放不下（会被圆整成 0.00000001，误差 43%）——spec 纪律是数值以官方页
-- 为准，精度随官方价位走。
-- ========================================================================

CREATE TABLE met_price_entries (
    id             BIGINT       PRIMARY KEY,  -- TSID（应用侧 @PrePersist 生成，全仓惯例）
    provider       VARCHAR(50)  NOT NULL,   -- 模型提供方（与 UsageEvent.provider 同键）
    model          VARCHAR(100) NOT NULL,   -- 模型（与 UsageEvent.model 同键）
    token_kind     INT          NOT NULL,   -- TokenKind：1=input 2=output 3=cache_read 4=cache_write 5=reasoning
    unit_price     NUMERIC(20,10) NOT NULL, -- 每 token 单价（如 $1.5/M = 0.0000015）
    currency       VARCHAR(10)  NOT NULL,   -- ISO 4217 币种（'USD' / 'CNY'，按 provider 官方计价币种）
    effective_from TIMESTAMPTZ  NOT NULL,   -- 生效起点（含）
    effective_to   TIMESTAMPTZ,             -- 生效终点（不含）；NULL = 当前行
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     BIGINT,
    updated_by     BIGINT,
    CONSTRAINT uk_met_price_entries_key_from UNIQUE (provider, model, token_kind, effective_from)
);

-- 换算匹配面（provider, model, kind 等值 + effective_from 区间）由唯一约束的自建
-- 索引覆盖，不另建冗余索引
