-- ========================================================================
-- A4 · business.task：任务系统核心（票 #26，A4 规格 §1/§2）
-- tsk_tasks = 测试外包循环的人任务（五要素：内容/执行方/状态/确认模式/类型——
-- 确认模式不落列，由 type 推导：TEST → 人工确认）；tsk_bugs = Bug 三态
-- （OPEN/FIXED/VERIFIED，复测通过是唯一关闭态）。任务/Bug 项目级、与过程正交
-- （期后修复不锁，A3 缝③）。修复编排链与回填续跑随 #27，本票只落核心。
--
-- 级联口径：project 真删级联清 tsk_* 行（FK CASCADE，与 prj_* 同款——同库
-- 内依赖方行随主体存亡，不留孤儿）；tsk_bugs 随来源任务级联。assignee 账号是
-- idn_accounts 软引用（跨 BC 无 FK，同 prj_projects.owner_account_id 口径）。
-- ========================================================================

CREATE TABLE tsk_tasks (
    id                  BIGINT PRIMARY KEY,     -- TSID（taskId 即其字符串形）
    project_id          BIGINT NOT NULL,        -- 所属项目（prj_projects FK 级联）
    type                INT NOT NULL,           -- TaskType：1=TEST（v1 单值；自动确认类型是加 type 时的映射扩展）
    title               VARCHAR(200) NOT NULL,  -- 任务标题
    content             TEXT NOT NULL,          -- 任务内容（测试要求等）
    assignee_account_id BIGINT NOT NULL,        -- 指派账号（idn_accounts 软引用；v1 指派必填，领取模式在雾）
    status              INT NOT NULL,           -- TaskStatus：1=已发布 2=执行中 3=已提交 4=已确认 5=已取消
    wait_id             VARCHAR(50),            -- 转任务来源的不透明引用（agt_pending_waits.wait_id，可空，A1 §3；回填随 #27）
    submitted_payload   JSONB,                  -- 已提交载荷暂存（报告 + Bug 清单 / 复测结果；驳回重交覆盖）
    reject_reason       VARCHAR(1000),          -- 驳回理由（重新提交时清空）
    rejected_at         TIMESTAMP,              -- 驳回时间（TASK_REJECTED 待办判定：IN_PROGRESS ∧ 非空）
    confirmed_at        TIMESTAMP,              -- 确认时间（终态事实）
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          BIGINT,
    updated_by          BIGINT,
    CONSTRAINT fk_tsk_tasks_project FOREIGN KEY (project_id)
        REFERENCES prj_projects (id) ON DELETE CASCADE
);

-- dev 项目任务全量 + opc 指派清单的查询面
CREATE INDEX idx_tsk_tasks_project ON tsk_tasks (project_id);
CREATE INDEX idx_tsk_tasks_assignee ON tsk_tasks (assignee_account_id, status);

CREATE TABLE tsk_bugs (
    id             BIGINT PRIMARY KEY,     -- TSID
    project_id     BIGINT NOT NULL,        -- 所属项目（与任务同项目级正交记录）
    source_task_id BIGINT NOT NULL,        -- 来源测试任务（确认时入库的溯源键）
    title          VARCHAR(200) NOT NULL,  -- Bug 标题
    description    TEXT,                   -- 描述
    repro_steps    TEXT,                   -- 复现步骤（v1 文字，附件上传通道在雾）
    severity       INT NOT NULL,           -- BugSeverity：1=致命 2=严重 3=一般 4=轻微
    attachments    JSONB,                  -- URL 数组（v1 留缝无上传通道，恒 NULL）
    status         INT NOT NULL,           -- BugStatus：1=OPEN 2=FIXED 3=VERIFIED
    fix_run_id     VARCHAR(100),           -- 修复 run（修复编排链随 #27；G3/RETEST 谓词读）
    fix_note       TEXT,                   -- 修复 run 最终消息（「未重现」等结论如实记）
    closed_reason  VARCHAR(1000),          -- 手工关闭理由（bogus Bug，端点随 #27）
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     BIGINT,
    updated_by     BIGINT,
    CONSTRAINT fk_tsk_bugs_project FOREIGN KEY (project_id)
        REFERENCES prj_projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_tsk_bugs_task FOREIGN KEY (source_task_id)
        REFERENCES tsk_tasks (id) ON DELETE CASCADE
);

-- 项目 Bug 面板与 G3 谓词（open = status ≠ VERIFIED）
CREATE INDEX idx_tsk_bugs_project ON tsk_bugs (project_id);
CREATE INDEX idx_tsk_bugs_source_task ON tsk_bugs (source_task_id);
