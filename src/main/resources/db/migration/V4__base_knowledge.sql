-- ========================================================================
-- base.knowledge：知识块入库与语义检索（B0 蓝图 §2 片3 + A5 §2 存储模型定稿）
-- 单表 knw_chunks：五类素材（ARTIFACT/QA/FEEDBACK/TEST_REPORT/BUG，A5 §1）
-- 分块后各占一行——kind 对底座不透明（业务词汇），幂等键 = (kind, source_ref)
-- 删后插；无资产表、无维度标签，meta jsonb 留扩展位（stage/severity 等）。
-- 检索 = 全局跨项目纯相似（A5 §3），embedding 列 HNSW 余弦索引承载。
-- ========================================================================

CREATE TABLE knw_chunks (
    id           BIGINT PRIMARY KEY,     -- TSID（入库时应用侧生成，见 PgvectorChunkStore）
    kind         VARCHAR(30) NOT NULL,   -- 素材类别（业务定义，底座不解释）
    source_ref   VARCHAR(200) NOT NULL,  -- 素材来源标识（幂等键之一，A5 §1 五类各自形态）
    project_id   VARCHAR(100) NOT NULL,  -- 归属项目（purgeByProject 级联清理入口）
    project_name VARCHAR(200) NOT NULL,  -- 来源项目名（命中条目展示）
    title        VARCHAR(300) NOT NULL,  -- 素材标题（命中条目展示）
    seq          INT NOT NULL,           -- 块序（同素材内分块顺序）
    chunk        TEXT NOT NULL,          -- 块文本
    embedding    vector(512) NOT NULL,   -- 块向量（本机 fastembed，BAAI/bge-small-zh-v1.5 512 维）
    meta         JSONB,                  -- 扩展位（null/空归一为 NULL，底座不解释）
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   BIGINT,
    updated_by   BIGINT
);

-- 幂等删插键：index 时 DELETE ... WHERE kind = ? AND source_ref = ?
CREATE INDEX idx_knw_chunks_kind_source_ref ON knw_chunks (kind, source_ref);

-- 项目删除级联清理入口（B0 §6 步骤 6 清理范围随含 knw_）
CREATE INDEX idx_knw_chunks_project_id ON knw_chunks (project_id);

-- HNSW 余弦近邻索引（检索 ORDER BY embedding <=> ?::vector LIMIT k）
CREATE INDEX idx_knw_chunks_embedding ON knw_chunks USING hnsw (embedding vector_cosine_ops);
