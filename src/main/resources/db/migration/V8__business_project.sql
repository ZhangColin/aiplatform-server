-- ========================================================================
-- 片5a · business.project：项目聚合与期（B0 蓝图 §2 片5 / A3 §2.1）
-- 项目 = 用户的长期实体（业务字段 + 工作区引用 + 归属账号 + 归档终点）；
-- 期 = 过程覆盖层（状态机主体在期：stage / stage_task_count / OPEN|CLOSED），
-- 建项目即同事务建第 1 期（seq=1 OPEN），每项目至多一个 OPEN（部分唯一索引）。
-- 建项目真删级联（workspace 由编排清理，prj_* 行 FK 级联），无软删除——
-- 继承 Auditable 只取审计字段。prj_confirmations / prj_demand_pool_entries
-- 归片5b/5c（票 #23/#24），不进本迁移。
-- ========================================================================

CREATE TABLE prj_projects (
    id               BIGINT PRIMARY KEY,     -- TSID（projectId 即其字符串形）
    name             VARCHAR(100) NOT NULL,  -- 项目名（用户起）
    type             INT NOT NULL,           -- ProjectType：1=WEBSITE 2=ECOMMERCE
    engine           VARCHAR(20) NOT NULL,   -- 开发智能体引擎（注册表键：opencode / dsh）
    workspace_id     BIGINT NOT NULL,        -- dev 工作区（wsp_workspaces 跨上下文软引用，无 FK）
    owner_account_id BIGINT,                 -- 归属账号（idn_accounts 软引用，A2：创建时填、v1 不过滤）
    archived_at      TIMESTAMP,              -- 归档时间（A3 §4：单向终点；NULL = 未归档）
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       BIGINT,
    updated_by       BIGINT
);

-- 工作区寻址查询面（删除级联与工作区接回的反查）
CREATE INDEX idx_prj_projects_workspace ON prj_projects (workspace_id);

-- 期（状态机主体在期，A3 §2.1）：stage 存主链定义的阶段名稳定键（base.process StageEntry.name）
CREATE TABLE prj_iterations (
    id               BIGINT PRIMARY KEY,     -- TSID
    project_id       BIGINT NOT NULL,
    seq              INT NOT NULL,           -- 期序（v1 恒 1：每项目 1 期）
    stage            VARCHAR(20) NOT NULL,   -- 当前阶段名（BA/DEMO/DEV/TEST/ACCEPTANCE/CLOSED）
    stage_task_count INT NOT NULL DEFAULT 0, -- 当前阶段任务计数（门禁输入；阶段推进时归零）
    status           INT NOT NULL,           -- IterationStatus：1=OPEN 2=CLOSED
    closed_at        TIMESTAMP,              -- 收口时间（验收门通过联动关期）
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       BIGINT,
    updated_by       BIGINT,
    -- 项目删除级联清期行（真删，A3 §4：DELETE 级联不变）
    CONSTRAINT fk_prj_iterations_project FOREIGN KEY (project_id)
        REFERENCES prj_projects (id) ON DELETE CASCADE
);

-- v1 寻址：项目的 OPEN 期至多一个（A3 §2.1）
CREATE UNIQUE INDEX uk_prj_iterations_open ON prj_iterations (project_id) WHERE status = 1;
CREATE INDEX idx_prj_iterations_project ON prj_iterations (project_id);
