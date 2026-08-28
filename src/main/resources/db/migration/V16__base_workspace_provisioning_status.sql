-- ========================================================================
-- #60 · base.workspace：工作区置备状态（#58 异步化 prefactor）
-- 置备状态落库：provisioning（置备中）→ ready（就绪）/ failed（失败）。
-- 对话与环境就绪解耦——创建先落记录（置备中、端口 0），docker 后台收敛后回填。
-- 存量行全部是「已置备完成」的同步创建产物，DEFAULT 2（READY）兜底零迁移。
-- ========================================================================

ALTER TABLE wsp_workspaces
    ADD COLUMN provisioning_status INT NOT NULL DEFAULT 2;  -- ProvisioningStatus：1=PROVISIONING 2=READY 3=FAILED
