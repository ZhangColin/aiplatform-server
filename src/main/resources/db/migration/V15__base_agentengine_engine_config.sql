-- ========================================================================
-- 片2 · base.agentengine：全局引擎配置 agt_engine_config（票 #42）
-- 平台「当前生效引擎」的落库事实：后台查看/切换（/api/admin/engine-config），
-- 服务端统一配置——引擎选择从创建参数与用户界面移除（用户不懂引擎）。
--
-- 单行表：id 钉死 1（应用侧工厂 SINGLETON_ID，非 TSID），全平台恒一行。
-- 无行 = 未配置：读侧回落注册表缺省（opencode），不种子——代码缺省与库值
-- 不劈叉（种子反而制造双源）。
--
-- 生效口径 = 新项目生效、存量不迁：切换只影响此后创建的项目（创建时读本表
-- 固化进 prj_projects.engine，该列 updatable=false 既有事实），存量项目固化
-- 创建时引擎跑完，零迁移。
-- ========================================================================

CREATE TABLE agt_engine_config (
    id            BIGINT PRIMARY KEY,     -- 单例行固定 1（应用侧钉死，不 TSID）
    active_engine VARCHAR(50) NOT NULL,   -- 生效引擎名（注册表键：opencode / dsh）
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    BIGINT,
    updated_by    BIGINT
);
