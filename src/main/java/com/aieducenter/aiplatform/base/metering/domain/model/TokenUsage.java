package com.aieducenter.aiplatform.base.metering.domain.model;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.base.metering.domain.error.MeteringMessage;

/**
 * token 用量五档（CONTEXT.md「计量上下文」：input/output/cache_read/cache_write/reasoning）。
 *
 * <p><b>口径 = 互斥分解</b>：五档各自独立、加和（{@link #total()}）恒等于提供方
 * total 口径——OpenAI/DeepSeek 的 prompt+completion、Anthropic 的
 * input+cache_read+cache_write+output。缓存命中/写入与推理 token 从总量中拆出单列，
 * 是 A6「平台成本 = Σ(token_kind × 单价)」按档计价不重复计费的前提。只记 token
 * 不记钱（零商业概念），金额换算归查询侧现算（A6）。</p>
 *
 * <p>三家协议归一化经工厂方法进入：OpenAI（cached/reasoning 是总量子集，做减法）、
 * Anthropic（官方原生分列，直取）、DeepSeek（prompt = hit + miss，miss 归 input、
 * hit 归 cache_read）。协议自相矛盾（子集大于总量）会分解出负数，由构造不变量拒收。</p>
 */
public record TokenUsage(long input, long output, long cacheRead, long cacheWrite, long reasoning) {

    /** 全零用量（空聚合结果的 total 基线）。 */
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0, 0);

    public TokenUsage {
        if (input < 0 || output < 0 || cacheRead < 0 || cacheWrite < 0 || reasoning < 0) {
            throw new DomainException(MeteringMessage.TOKEN_USAGE_NEGATIVE);
        }
    }

    /**
     * OpenAI 协议归一化：input = prompt − cached、output = completion − reasoning
     * （cached ⊆ prompt、reasoning ⊆ completion，拆出后单列）；cache_write 无此口径。
     */
    public static TokenUsage fromOpenAiProtocol(OpenAiTokenUsage sample) {
        long cacheRead = orZero(sample.cachedTokens());
        long reasoning = orZero(sample.reasoningTokens());
        return new TokenUsage(
                sample.promptTokens() - cacheRead,
                sample.completionTokens() - reasoning,
                cacheRead, 0, reasoning);
    }

    /**
     * Anthropic 协议归一化：官方原生分列直取（input 本就不含缓存读写）；
     * reasoning 无独立口径（thinking 含在 output 内）。
     */
    public static TokenUsage fromAnthropicProtocol(AnthropicTokenUsage sample) {
        return new TokenUsage(
                sample.inputTokens(),
                sample.outputTokens(),
                orZero(sample.cacheReadInputTokens()),
                orZero(sample.cacheCreationInputTokens()),
                0);
    }

    /**
     * DeepSeek 协议归一化：input = prompt − hit（即未命中 miss，按普通输入计价）、
     * cache_read = hit；cache_write/reasoning 无此口径。
     */
    public static TokenUsage fromDeepSeekProtocol(DeepSeekTokenUsage sample) {
        long cacheRead = orZero(sample.promptCacheHitTokens());
        return new TokenUsage(
                sample.promptTokens() - cacheRead,
                sample.completionTokens(),
                cacheRead, 0, 0);
    }

    /**
     * 五档加和（= 提供方 total 口径）。
     */
    public long total() {
        return input + output + cacheRead + cacheWrite + reasoning;
    }

    /**
     * 逐档相加（聚合累加用）。
     */
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                input + other.input,
                output + other.output,
                cacheRead + other.cacheRead,
                cacheWrite + other.cacheWrite,
                reasoning + other.reasoning);
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }
}
