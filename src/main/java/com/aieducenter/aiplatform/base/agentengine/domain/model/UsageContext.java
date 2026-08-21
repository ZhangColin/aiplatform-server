package com.aieducenter.aiplatform.base.agentengine.domain.model;

import java.util.Map;

/**
 * 计量归属（A1 §2.4）：业务编排层随任务下发透传，底座不解释——{@code subject} 为
 * 不透明归属 id（业务层定，如 projectId；底座任务端点以 workspaceId 兜底归属），
 * {@code dims} 业务维度（role/stage 等，原样进 UsageEvent）。
 *
 * <p>run 级用量事件的上报身份：OpenCode 适配器 run 结束以此组装 UsageEvent
 * （DSH headless 无 usage，不构造不透传）。</p>
 */
public record UsageContext(String subject, Map<String, String> dims) {

    public UsageContext {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("UsageContext.subject 不能为空（计量归属必填）");
        }
        dims = dims == null ? Map.of() : Map.copyOf(dims);
    }
}
