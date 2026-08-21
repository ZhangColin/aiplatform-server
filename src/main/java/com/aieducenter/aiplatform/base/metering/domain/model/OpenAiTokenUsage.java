package com.aieducenter.aiplatform.base.metering.domain.model;

/**
 * OpenAI Chat Completions 协议 usage 样例（研究稿 F1.1）：prompt/completion 总量 +
 * details 明细——{@code prompt_tokens_details.cached_tokens} 是 prompt 的子集
 * （缓存命中），{@code completion_tokens_details.reasoning_tokens} 是 completion
 * 的子集（推理输出，o 系列）。明细对象可能缺失（null = 无该口径）。
 */
public record OpenAiTokenUsage(
        long promptTokens,
        long completionTokens,
        Long cachedTokens,
        Long reasoningTokens) {
}
