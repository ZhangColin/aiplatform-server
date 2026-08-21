package com.aieducenter.aiplatform.base.knowledge.domain.repository;

import java.util.List;

import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeSpec;

/**
 * 知识块存取（{@code knw_chunks}）：pgvector 向量列超出 JPA 映射面，写读两侧
 * 都走原生 SQL（照 {@code UsageEventAggregations} 先例：接口在 domain/repository，
 * JdbcTemplate 实现在 infrastructure，不挂 Spring Data 仓储）。
 */
public interface ChunkStore {

    /**
     * 幂等落库：{@code (kind, sourceRef)} 删后插，同一事务内完成（新旧块原子切换）。
     * embeddings 与 {@link KnowledgeSpec#chunks()} 同序同量（调用方保证）。
     */
    void replace(KnowledgeSpec spec, List<float[]> embeddings);

    /**
     * 余弦相似检索（HNSW 索引）：全局跨项目、按相似度升序取 topK（A5 §3 策略）。
     */
    List<KnowledgeHit> findSimilar(float[] queryVector, int topK);

    /**
     * 按项目清理全部知识块（级联清理入口，A5 §5）。
     */
    void deleteByProject(String projectId);
}
