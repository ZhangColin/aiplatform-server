package com.aieducenter.aiplatform.base.metering.domain.model;

/**
 * Anthropic Messages 协议 usage 样例（研究稿 F1.2）：input/output 命名，缓存分
 * 「创建/读」两类独立计数——{@code input_tokens} 本就不含缓存读写（官方原生分列）；
 * {@code output_tokens} 是计费权威总量口径（含 thinking，协议未单列 reasoning）。
 * 缓存字段可能缺失（null = 无缓存）。
 */
public record AnthropicTokenUsage(
        long inputTokens,
        long outputTokens,
        Long cacheReadInputTokens,
        Long cacheCreationInputTokens) {
}
