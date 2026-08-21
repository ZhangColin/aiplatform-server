-- ========================================================================
-- base.metering：用量事件采集（A1 §2.1/§2.2，B0 蓝图修订 A1 新增第六 BC）
-- run 级 UsageEvent 一条：event_id 为调用方生成的幂等键（重复上报 first-write-wins，
-- 主键即幂等约束）；subject 不透明（业务层传 projectId，底座不解释）；dims 业务维度
-- 透传（role/stage 等，A6 再扩 iterationId）；只记 token 不记钱（零商业概念）——
-- 单价表 met_price_entries 与平台成本换算归 A6（票 #29）。
--
-- 五档口径 = 互斥分解：input/output/cache_read/cache_write/reasoning 各自独立，
-- 加和 = 提供方 total 口径（OpenAI prompt−cached / DeepSeek miss→input、hit→cache_read；
-- 归一化见 TokenUsage），支撑 A6「平台成本 = Σ(token_kind × 单价)」不重复计费。
-- ========================================================================

CREATE TABLE met_usage_events (
    event_id    VARCHAR(64) PRIMARY KEY,  -- 幂等键（调用方生成：run 级事件建议 UUID）
    ts          TIMESTAMPTZ NOT NULL,     -- 事件发生时间（bySubject 聚合的时间轴）
    subject     VARCHAR(100) NOT NULL,    -- 不透明归属 id（业务层定；零业务概念）
    run_id      VARCHAR(100),             -- 运行标识（可空）
    session_id  VARCHAR(100),             -- agent 会话标识（可空）
    provider    VARCHAR(50) NOT NULL,     -- 模型提供方（A6 单价表匹配键之一）
    model       VARCHAR(100) NOT NULL,    -- 模型（A6 单价表匹配键）
    engine      VARCHAR(50) NOT NULL,     -- coding agent 引擎（opencode/dsh）
    dims        JSONB,                    -- 业务维度透传（底座不解释；无维度为 NULL）
    input       BIGINT NOT NULL,          -- 普通输入 token（不含缓存命中/缓存写）
    output      BIGINT NOT NULL,          -- 普通输出 token（不含推理）
    cache_read  BIGINT NOT NULL,          -- 缓存命中读
    cache_write BIGINT NOT NULL,          -- 缓存写
    reasoning   BIGINT NOT NULL,          -- 推理输出（OpenAI o 系列等）
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  BIGINT,
    updated_by  BIGINT
);

-- bySubject 聚合（subject + 时间窗）查询面
CREATE INDEX idx_met_usage_events_subject_ts ON met_usage_events (subject, ts);
