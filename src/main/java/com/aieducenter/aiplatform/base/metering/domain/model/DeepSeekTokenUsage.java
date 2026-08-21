package com.aieducenter.aiplatform.base.metering.domain.model;

/**
 * DeepSeek 协议 usage 样例（研究稿 F1.3，OpenAI 兼容形态）：上下文缓存按
 * 命中/未命中分别计价——{@code prompt_tokens = prompt_cache_hit_tokens +
 * prompt_cache_miss_tokens}；reasoner 的 CoT 计入 completion（协议未单列）。
 * 缓存字段可能缺失（null = 无缓存）。
 */
public record DeepSeekTokenUsage(
        long promptTokens,
        long completionTokens,
        Long promptCacheHitTokens,
        Long promptCacheMissTokens) {
}
