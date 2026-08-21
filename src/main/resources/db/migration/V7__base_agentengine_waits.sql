-- ========================================================================
-- 片2b · base.agentengine：统一等待点落库（A1 §1.1 口子① / 票 #21）
-- 等待点 = agent 运行中挂起等人反馈的底座实体（问答/权限统一模型）：waitId 是
-- 平台生成的稳定标识（跨重启存活、业务层不透明引用的回填键），中性寻址无
-- projectId。body = 引擎载荷原样（底座不解释）；summary = 适配器提取的中性
-- 短文本（SSE wait-raised 同源）；settle_outcome 仅 SETTLED 时有值
-- （answered/approved/denied/deferred）。engine_ref = 引擎侧请求/权限 id，
-- 是 settle 时答复派发键。raise 幂等 = 同 (session_id, engine_ref) 至多一行
-- PENDING（部分唯一索引兜底；终态行保留为历史，不挡引擎侧同挂起的再登记）。
-- kind/status/settle_outcome 为 BaseEnum 整数码（1起，与域内枚举同序）。
-- 不软删除（终态行即历史，Auditable 只取审计字段）。
-- ========================================================================

CREATE TABLE agt_pending_waits (
    wait_id        VARCHAR(50) PRIMARY KEY,    -- TSID 十进制字符串（平台生成）
    workspace_id   BIGINT NOT NULL,            -- 按工作区中性寻址
    session_id     VARCHAR(100) NOT NULL,      -- 引擎会话标识原样
    run_id         VARCHAR(100) NOT NULL,      -- 所属一次运行（deny 计数/终态联动的锚）
    engine_ref     VARCHAR(100) NOT NULL,      -- 引擎侧 id（que_* / permission id）
    kind           INT NOT NULL,               -- 1=QUESTION 2=PERMISSION
    status         INT NOT NULL,               -- 1=PENDING 2=SETTLED 3=EXPIRED 4=CANCELLED
    body           jsonb,                      -- 引擎载荷原样
    summary        VARCHAR(500),               -- 适配器提取的中性短文本
    settle_outcome INT,                        -- 1=answered 2=approved 3=denied 4=deferred
    raised_at      TIMESTAMP NOT NULL,
    settled_at     TIMESTAMP,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     BIGINT,
    updated_by     BIGINT
);

-- raise 幂等约束：同一引擎挂起同刻至多一行 PENDING（并发双登记库层拒绝）
CREATE UNIQUE INDEX uk_agt_pending_waits_pending_ref
    ON agt_pending_waits (session_id, engine_ref) WHERE status = 1;

-- 跨会话聚合面（工作区待处理入口）
CREATE INDEX idx_agt_pending_waits_workspace_status
    ON agt_pending_waits (workspace_id, status);

-- run 终态联动面（finish/error/timeout/cancel → 其 PENDING → EXPIRED）
CREATE INDEX idx_agt_pending_waits_run ON agt_pending_waits (run_id);
