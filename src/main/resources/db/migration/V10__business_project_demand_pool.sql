-- ========================================================================
-- 片5c · business.project：需求池（B0 蓝图 §2 片5 / A3 §4）
-- prj_demand_pool_entries：项目级、随时可记的需求/bug 收件清单——append-only
-- 只增不改，无状态列（「记录不等同开工」）；开新期时作为需求梳理输入。
-- kind 可空（收件时不强分类）；BUG 类型条目是收件记录不是缺陷实体（缺陷走 A4）。
-- created_at/created_by 即业务字段（记录时间/记录账号），append-only 无审计列；
-- 行随项目 FK 级联删除（需求池与项目同存亡）。
-- ========================================================================

CREATE TABLE prj_demand_pool_entries (
    id         BIGINT PRIMARY KEY,     -- TSID
    project_id BIGINT NOT NULL,        -- 所属项目
    content    VARCHAR(2000) NOT NULL, -- 收件内容（需求描述或缺陷反馈原文）
    kind       INT,                    -- DemandEntryKind：1=需求 2=缺陷（可空，不强分类）
    source     INT NOT NULL,           -- DemandSource：1=用户 2=测试 3=验收
    created_by BIGINT,                 -- 记录账号（idn_accounts 软引用；无会话上下文可空）
    created_at TIMESTAMP NOT NULL,     -- 记录时间（业务时间，收件清单排序锚点）
    -- 项目删除级联清收件行（真删，A3 §4：DELETE 级联不变）
    CONSTRAINT fk_prj_demand_pool_project FOREIGN KEY (project_id)
        REFERENCES prj_projects (id) ON DELETE CASCADE
);

-- 项目的收件清单时间线（新→旧查询面）
CREATE INDEX idx_prj_demand_pool_project ON prj_demand_pool_entries (project_id);
