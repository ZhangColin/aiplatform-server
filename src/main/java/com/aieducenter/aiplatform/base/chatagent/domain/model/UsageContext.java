package com.aieducenter.aiplatform.base.chatagent.domain.model;

import java.util.Map;

/**
 * 对话智能体的计量归属（#44）：口径与 {@code base.agentengine.domain.model.UsageContext}
 * 一致（A1 §2.4：subject 为不透明归属 id，业务层定；dims 业务维度原样进 UsageEvent），
 * 分域持有避免 chatagent 与编码引擎域互相耦合。为空则本轮对话不上报用量。
 */
public record UsageContext(String subject, Map<String, String> dims) {

    public UsageContext {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("UsageContext.subject 不能为空（计量归属必填）");
        }
        dims = dims == null ? Map.of() : Map.copyOf(dims);
    }
}
