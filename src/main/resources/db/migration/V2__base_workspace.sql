-- ========================================================================
-- 片1b · base.workspace：工作区与中间件资源落库（B0 蓝图 §2 片1）
-- 工作区 = 底座环境句柄的持久化记录（服务重启后按记录接回容器）；
-- 中间件资源 = 随环境供给的 pg/redis（容器名 + 宿主端口 + 容器网络内连接串）。
-- 生命周期与记录同生共死（销毁级联清理），无软删除——继承 Auditable 只取审计字段。
-- ========================================================================

CREATE TABLE wsp_workspaces (
    id              BIGINT PRIMARY KEY,          -- TSID（workspaceId 即其字符串形）
    kind            INT NOT NULL,                -- EnvKind：1=DEV 2=TEST 3=PROD
    container_name  VARCHAR(100) NOT NULL,       -- dev 容器名（exec/预览/清理的锚点）
    network_name    VARCHAR(100) NOT NULL,       -- 项目专属 docker network
    host_port       INT NOT NULL,                -- dev：引擎接入点宿主端口（片2 消费；runtime 0）
    preview_port    INT NOT NULL,                -- dev：预览宿主端口（runtime 0）
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT,
    updated_by      BIGINT,
    CONSTRAINT uq_wsp_workspaces_container UNIQUE (container_name)
);

-- 中间件资源（一工作区一 pg 一 redis，随工作区聚合级联增删）
CREATE TABLE wsp_resources (
    id              BIGINT PRIMARY KEY,          -- TSID
    workspace_id    BIGINT NOT NULL,
    kind            INT NOT NULL,                -- MiddlewareKind：1=POSTGRESQL 2=REDIS
    container_name  VARCHAR(100) NOT NULL,       -- 资源容器名（销毁级联与接回的锚点）
    host_port       INT NOT NULL,                -- 宿主机映射端口（本地工具直连）
    internal_url    VARCHAR(500) NOT NULL,       -- 容器网络内连接串（/workspace/.env 注入原文，含凭据）
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT,
    updated_by      BIGINT,
    CONSTRAINT uq_wsp_resources_workspace_kind UNIQUE (workspace_id, kind),
    -- 聚合删除 = 级联删资源行（@OnDelete 同语义，避免单向关联的置空更新）
    CONSTRAINT fk_wsp_resources_workspace FOREIGN KEY (workspace_id)
        REFERENCES wsp_workspaces (id) ON DELETE CASCADE
);
