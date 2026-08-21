-- ========================================================================
-- 片2a · base.agentengine：agent 会话落库（B0 蓝图 §2 片2 / A1 §1.2）
-- 会话 = 跨运行的持久寻址（与 runId「一次运行」并存不混淆）：runTask 建会话即登记、
-- 复用 sessionId 续跑即刷新最近运行；按 workspaceId 寻址、跨重启存活（重启接回的
-- 寻址面）。sessionId 为引擎侧标识原样（opencode ses_* / dsh 适配器自生成 dsh-*）。
-- 不软删除（与运行记录同生共死由持有方管理，Auditable 只取审计字段）。
-- 等待点表 agt_pending_waits 归片2b（票 #21），不进本迁移。
-- ========================================================================

CREATE TABLE agt_agent_sessions (
    id           BIGINT PRIMARY KEY,          -- TSID
    session_id   VARCHAR(100) NOT NULL,       -- 引擎会话标识原样（引擎侧寻址键）
    workspace_id BIGINT NOT NULL,             -- 按工作区寻址（底座中性键，无 projectId）
    engine       VARCHAR(50) NOT NULL,        -- 引擎名（注册表键：opencode / dsh）
    last_run_id  VARCHAR(100) NOT NULL,       -- 最近一次运行（续跑刷新）
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   BIGINT,
    updated_by   BIGINT,
    CONSTRAINT uk_agt_agent_sessions_engine_session UNIQUE (engine, session_id)
);

-- 工作区寻址查询面（重启后会话可寻址）
CREATE INDEX idx_agt_agent_sessions_workspace ON agt_agent_sessions (workspace_id);
