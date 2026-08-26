-- ========================================================================
-- base.chatagent：对话智能体会话状态落库（#48 会话恢复）
-- AgentScope AgentState 的 PostgreSQL 存储（cat_agent_state）：对话历史/压缩
-- 摘要/权限规则/Plan Mode/tool state 按 (user_id, session_id) 槽位寻址——平台
-- 重启后同一会话标识可恢复续跑（访谈上下文不丢）。单值 = item_index 0 一行；
-- 列表 = 逐项一行增量 append（{key}:_hash 辅助行做变更检测：hash 变/缩短 →
-- 全量重写，纯增长 → 只插新项，对齐 agentscope MysqlAgentStateStore 语义）。
-- state_data 为 TEXT（JSON 字符串与 hash 字符串混存，不做 jsonb 解释）。
-- 不软删除（会话状态是运行时数据，无审计面）。
-- ========================================================================

CREATE TABLE cat_agent_state (
    user_id     VARCHAR(255) NOT NULL,     -- 规范化 userId（匿名桶 __anon__）
    session_id  VARCHAR(255) NOT NULL,     -- AgentScope 会话标识原样
    state_key   VARCHAR(255) NOT NULL,     -- 状态键（agent_state / memory_messages / *_hash）
    item_index  INT NOT NULL DEFAULT 0,    -- 单值恒 0；列表为序号
    state_data  TEXT NOT NULL,             -- JSON 序列化状态值（hash 行存 hash 字符串）
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, session_id, state_key, item_index)
);

-- 会话槽位整体删除面（delete session）
CREATE INDEX idx_cat_agent_state_session ON cat_agent_state (user_id, session_id);
