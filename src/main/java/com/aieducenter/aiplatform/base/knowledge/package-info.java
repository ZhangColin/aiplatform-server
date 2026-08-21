/**
 * Knowledge Context（base.knowledge）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>知识入库 / 检索（KnowledgePort：index(内容+元数据) / retrieve(query, topK)）</li>
 *   <li>EmbeddingClient（本机 fastembed :9091，512 维）+ pgvector HNSW 余弦检索</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>底座知识能力：「哪个阶段产出什么文件」是业务知识，归 business.project 在调用
 * 端口时传入。表前缀 {@code knw_}（单表 knw_chunks，kind/source_ref 幂等删插），
 * 错误码前缀 {@code KNW_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：模型（KnowledgeSpec/KnowledgeHit）、端口（KnowledgePort 北向入口
 *       / EmbeddingClient 南向向量化）、ChunkStore 存取接口、KNW_ 错误</li>
 *   <li>application - 应用层：KnowledgeAppService（入库幂等删后插 / 检索降级 / 级联清理）</li>
 *   <li>infrastructure - 基础设施层：KnowledgeLocalAdapter（端口进程内适配）、
 *       persistence/PgvectorChunkStore（JdbcTemplate + pgvector）、embedding/FastembedEmbeddingClient</li>
 *   <li>endpoints - 北向接口：暂无（五类摄取挂钩与 search 端点归 A5 接线票 #28）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Knowledge", subDomain = SubDomain.SUPPORTING)
package com.aieducenter.aiplatform.base.knowledge;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
