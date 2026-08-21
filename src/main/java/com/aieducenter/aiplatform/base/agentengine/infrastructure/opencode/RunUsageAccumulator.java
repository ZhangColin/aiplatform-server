package com.aieducenter.aiplatform.base.agentengine.infrastructure.opencode;

import com.fasterxml.jackson.databind.JsonNode;

import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;

/**
 * run 级 token 用量累加器（A1 §2.3：OpenCode 无总用量字段，tokens 为每个
 * step-finish 的逐步增量，求和归 OpenCodeAdapter 内部，不进协议）。
 *
 * <p>opencode step-finish 的 tokens 形态（1.18 一手核对遗留为 PoC 项，防御式解析）：
 * {@code {input, output, reasoning, cache: {read, write}}}——与 Anthropic 原生分列
 * 同构，五档直取。缺字段按 0 计（无数据不造数），run 结束以总量上报恰一条。</p>
 */
public final class RunUsageAccumulator {

    private long input;
    private long output;
    private long cacheRead;
    private long cacheWrite;
    private long reasoning;

    /**
     * 累加一个 step-finish part 的 token 增量。
     */
    public void addStepFinish(JsonNode part) {
        JsonNode tokens = part.path("tokens");
        input += tokens.path("input").asLong(0);
        output += tokens.path("output").asLong(0);
        cacheRead += tokens.path("cache").path("read").asLong(0);
        cacheWrite += tokens.path("cache").path("write").asLong(0);
        reasoning += tokens.path("reasoning").asLong(0);
    }

    /**
     * 五档总量（互斥分解口径，见 {@link TokenUsage}）。
     */
    public TokenUsage total() {
        return new TokenUsage(input, output, cacheRead, cacheWrite, reasoning);
    }
}
