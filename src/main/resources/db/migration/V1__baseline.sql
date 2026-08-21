-- ========================================================================
-- 片0 · 工程基线：平台 PG 扩展就位（B0 蓝图 §2 片0）
-- pgvector 向量扩展，base.knowledge 的向量检索依赖（knw_chunks，片3）。
-- 要求实例镜像内置 pgvector（本机 dev 用 pgvector/pgvector:pg16，
-- 启动方式见 docs/guide/本机依赖启动.md）。
-- ========================================================================

CREATE EXTENSION IF NOT EXISTS vector;
