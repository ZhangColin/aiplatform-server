package com.aieducenter.aiplatform.business.project.application.dto.response;

import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;

/**
 * 知识检索单条命中（A5 §4 演示端点响应）：kind 为入库时的素材类别原样透出
 * （ARTIFACT/QA/FEEDBACK/TEST_REPORT/BUG，对底座不透明），projectName 是来源
 * 项目（跨项目命中是特性）。
 *
 * @param kind        素材类别
 * @param projectName 来源项目名
 * @param title       素材标题
 * @param snippet     命中块文本
 */
public record KnowledgeSearchItemResponse(
        String kind,
        String projectName,
        String title,
        String snippet) {

    public static KnowledgeSearchItemResponse of(KnowledgeHit hit) {
        return new KnowledgeSearchItemResponse(hit.kind(), hit.sourceProjectName(),
                hit.title(), hit.chunk());
    }
}
