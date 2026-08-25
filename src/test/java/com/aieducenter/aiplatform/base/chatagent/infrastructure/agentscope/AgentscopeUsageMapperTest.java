package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentscopeUsageMapper} 口径（#44）：ChatUsage 的 inputTokens 含缓存、
 * cachedTokens 为其子集——与 DeepSeek 协议同构（input = prompt − hit）。
 */
class AgentscopeUsageMapperTest {

    @Test
    void given_chat_usage_with_cache_when_map_then_cache_subtracted_from_input() {
        ChatUsage usage = new ChatUsage(100, 50, 30, 1.5);

        TokenUsage tokens = AgentscopeUsageMapper.toTokenUsage(usage);

        assertThat(tokens.input()).isEqualTo(70);
        assertThat(tokens.output()).isEqualTo(50);
        assertThat(tokens.cacheRead()).isEqualTo(30);
        assertThat(tokens.cacheWrite()).isZero();
        assertThat(tokens.reasoning()).isZero();
    }

    @Test
    void given_chat_usage_without_cache_when_map_then_input_kept_as_is() {
        ChatUsage usage = new ChatUsage(80, 20, 0, 0.5);

        TokenUsage tokens = AgentscopeUsageMapper.toTokenUsage(usage);

        assertThat(tokens.input()).isEqualTo(80);
        assertThat(tokens.output()).isEqualTo(20);
        assertThat(tokens.cacheRead()).isZero();
    }

    @Test
    void given_null_usage_when_map_then_zero() {
        assertThat(AgentscopeUsageMapper.toTokenUsage(null)).isEqualTo(TokenUsage.ZERO);
    }
}
