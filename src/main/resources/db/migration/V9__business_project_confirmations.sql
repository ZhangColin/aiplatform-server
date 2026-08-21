-- ========================================================================
-- 片5b · business.project：确认记录（A3 §3 / B0 蓝图 §2 片5）
-- prj_confirmations append-only（只增不改，无软删除）：门决策留痕——approve 也留痕
-- （交付审计 + A5 纪要素材；多账号是常态，account_id 第一天记 approver）。
-- 驳回 reason 必填（可空列，approve 恒 NULL）；随期 FK 级联删除。
-- ========================================================================

CREATE TABLE prj_confirmations (
    id           BIGINT PRIMARY KEY,     -- TSID
    iteration_id BIGINT NOT NULL,        -- 决策所针对于的期
    kind         INT NOT NULL,           -- ConfirmationKind：1=需求确认 2=Demo确认 3=开发完成确认 4=验收
    decision     INT NOT NULL,           -- ConfirmationDecision：1=通过 2=驳回
    reason       VARCHAR(1000),          -- 驳回理由（驳回必填，approve 恒空）
    account_id   BIGINT,                 -- 拍板账号（idn_accounts 软引用；无会话上下文可空）
    decided_at   TIMESTAMP NOT NULL,     -- 决策时间（业务时间，非审计时间）
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   BIGINT,
    updated_by   BIGINT,
    -- 期删除（项目删除级联）连带清确认行：留痕随过程主体存亡，不孤儿
    CONSTRAINT fk_prj_confirmations_iteration FOREIGN KEY (iteration_id)
        REFERENCES prj_iterations (id) ON DELETE CASCADE
);

-- 期维度的留痕时间线（验收审计/纪要回溯的查询面）
CREATE INDEX idx_prj_confirmations_iteration ON prj_confirmations (iteration_id);
