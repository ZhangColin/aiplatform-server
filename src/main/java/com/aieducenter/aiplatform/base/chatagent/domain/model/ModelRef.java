package com.aieducenter.aiplatform.base.chatagent.domain.model;

import com.aieducenter.aiplatform.base.chatagent.domain.error.ChatAgentMessage;
import com.cartisan.core.exception.DomainException;
import java.util.Set;

/**
 * 对话智能体模型引用（#44）：解析 {@code provider:modelId} 模型串，provider 白名单
 * 控制平台实际放开的模型提供方（AgentScope 模型扩展侧能力更宽，平台按需逐个放开——
 * 当前仅 deepseek，gemini 等后续加白）。
 *
 * <p>provider 口径与计量域 {@code UsageEvent.provider} 一致（如 {@code deepseek}），
 * 完整模型串透传 AgentScope {@code ModelRegistry} 解析。</p>
 */
public record ModelRef(String provider, String modelId) {

    /** 平台已放开的模型提供方（模型扩展模块就位后逐个加白） */
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("deepseek");

    public static ModelRef parse(String modelString) {
        if (modelString == null || modelString.isBlank()) {
            throw invalid(modelString);
        }
        String[] parts = modelString.trim().split(":");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw invalid(modelString);
        }
        String provider = parts[0];
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw invalid(modelString);
        }
        return new ModelRef(provider, parts[1]);
    }

    private static DomainException invalid(String modelString) {
        return new DomainException(ChatAgentMessage.MODEL_REF_INVALID,
                "不支持的模型串: " + modelString);
    }

    /** 还原 {@code provider:modelId} 完整串 */
    public String toModelString() {
        return provider + ":" + modelId;
    }
}
