package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import com.aieducenter.aiplatform.base.metering.domain.model.DeepSeekTokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import io.agentscope.core.model.ChatUsage;

/**
 * AgentScope {@link ChatUsage} → 平台 {@link TokenUsage}（#44 复用计量域既有解析）。
 *
 * <p>口径：ChatUsage 的 inputTokens 含缓存、cachedTokens 为其子集，与 DeepSeek
 * 协议同构（prompt = cache hit + miss），故走 {@code TokenUsage.fromDeepSeekProtocol}
 * （input = prompt − hit，cacheRead = hit；miss = prompt − hit 推导）。cacheWrite /
 * reasoning 在该协议无独立口径，归零。白名单放开新 provider 时按其协议口径分派。</p>
 */
final class AgentscopeUsageMapper {

    private AgentscopeUsageMapper() {
    }

    static TokenUsage toTokenUsage(ChatUsage usage) {
        if (usage == null) {
            return TokenUsage.ZERO;
        }
        long prompt = usage.getInputTokens();
        long cacheHit = usage.getCachedTokens();
        return TokenUsage.fromDeepSeekProtocol(new DeepSeekTokenUsage(
                prompt,
                usage.getOutputTokens(),
                cacheHit,
                prompt - cacheHit));
    }
}
