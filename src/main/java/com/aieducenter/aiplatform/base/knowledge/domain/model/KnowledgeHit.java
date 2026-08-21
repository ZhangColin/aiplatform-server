package com.aieducenter.aiplatform.base.knowledge.domain.model;

/**
 * 知识检索命中条目（A5 §2 形态）：注入 runTask 上下文与 SSE
 * {@code knowledge-retrieved} items 的最小展示面——类别、来源项目、标题、片段。
 *
 * @param kind              素材类别（入库时的 kind 透出）
 * @param sourceProjectName 来源项目名（跨项目命中是特性，A5 §3）
 * @param title             素材标题
 * @param chunk             命中块文本
 */
public record KnowledgeHit(
        String kind,
        String sourceProjectName,
        String title,
        String chunk) {
}
