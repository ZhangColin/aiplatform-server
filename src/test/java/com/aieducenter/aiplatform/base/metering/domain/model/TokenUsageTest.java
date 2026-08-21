package com.aieducenter.aiplatform.base.metering.domain.model;

import com.cartisan.core.exception.DomainException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TokenUsage 五档归一化单测（票 #16 验收：三家协议样例 → 统一五档）。
 *
 * <p>口径 = 互斥分解：五档各自独立、加和恒等于提供方 total 口径（OpenAI/DeepSeek 的
 * prompt+completion、Anthropic 的 input+cache_read+cache_write+output）——A6
 * 「平台成本 = Σ(token_kind × 单价)」按档计价不重复计费的前提。协议样例字段名
 * 照研究稿 F1.1–F1.3（11-token-metering）。</p>
 */
class TokenUsageTest {

    @Test
    void given_openai_sample_with_details_when_normalize_then_cache_and_reasoning_split_out() {
        // OpenAI Chat Completions usage：cached ⊆ prompt、reasoning ⊆ completion
        OpenAiTokenUsage sample = new OpenAiTokenUsage(1234, 567, 100L, 50L);

        TokenUsage tokens = TokenUsage.fromOpenAiProtocol(sample);

        assertThat(tokens.input()).isEqualTo(1134);      // 1234 − 100（缓存命中不重复计）
        assertThat(tokens.output()).isEqualTo(517);      // 567 − 50（推理单列）
        assertThat(tokens.cacheRead()).isEqualTo(100);
        assertThat(tokens.cacheWrite()).isZero();        // OpenAI 无缓存写口径
        assertThat(tokens.reasoning()).isEqualTo(50);
        // 加和 = 提供方 total 口径（prompt + completion）
        assertThat(tokens.total()).isEqualTo(1234 + 567);
    }

    @Test
    void given_openai_sample_without_details_when_normalize_then_zero_tiers() {
        // 非缓存/非推理模型：details 对象缺失（null 明细）
        OpenAiTokenUsage sample = new OpenAiTokenUsage(100, 200, null, null);

        TokenUsage tokens = TokenUsage.fromOpenAiProtocol(sample);

        assertThat(tokens).isEqualTo(new TokenUsage(100, 200, 0, 0, 0));
    }

    @Test
    void given_anthropic_sample_when_normalize_then_native_disjoint_mapping() {
        // Anthropic Messages usage：input 本就不含缓存读写，原生分列直取
        AnthropicTokenUsage sample = new AnthropicTokenUsage(1000, 800, 300L, 200L);

        TokenUsage tokens = TokenUsage.fromAnthropicProtocol(sample);

        assertThat(tokens.input()).isEqualTo(1000);
        assertThat(tokens.output()).isEqualTo(800);      // 权威总量口径（含 thinking）
        assertThat(tokens.cacheRead()).isEqualTo(300);
        assertThat(tokens.cacheWrite()).isEqualTo(200);
        assertThat(tokens.reasoning()).isZero();         // 协议未单列（含在 output 内）
        assertThat(tokens.total()).isEqualTo(1000 + 800 + 300 + 200);
    }

    @Test
    void given_anthropic_sample_without_cache_when_normalize_then_zero_cache_tiers() {
        AnthropicTokenUsage sample = new AnthropicTokenUsage(100, 50, null, null);

        TokenUsage tokens = TokenUsage.fromAnthropicProtocol(sample);

        assertThat(tokens).isEqualTo(new TokenUsage(100, 50, 0, 0, 0));
    }

    @Test
    void given_deepseek_sample_when_normalize_then_hit_miss_split() {
        // DeepSeek：prompt = hit + miss，miss 按普通 input 计、hit 按缓存命中计
        DeepSeekTokenUsage sample = new DeepSeekTokenUsage(1200, 300, 400L, 800L);

        TokenUsage tokens = TokenUsage.fromDeepSeekProtocol(sample);

        assertThat(tokens.input()).isEqualTo(800);       // prompt − hit（即 miss）
        assertThat(tokens.output()).isEqualTo(300);
        assertThat(tokens.cacheRead()).isEqualTo(400);
        assertThat(tokens.cacheWrite()).isZero();
        assertThat(tokens.reasoning()).isZero();         // reasoner 的 CoT 含在 completion 内
        assertThat(tokens.total()).isEqualTo(1200 + 300);
    }

    @Test
    void given_deepseek_sample_without_cache_fields_when_normalize_then_all_plain_input() {
        // hit/miss 字段缺失：退化为全普通 input
        DeepSeekTokenUsage sample = new DeepSeekTokenUsage(500, 100, null, null);

        TokenUsage tokens = TokenUsage.fromDeepSeekProtocol(sample);

        assertThat(tokens).isEqualTo(new TokenUsage(500, 100, 0, 0, 0));
    }

    @Test
    void given_inconsistent_openai_sample_when_normalize_then_negative_guard_trips() {
        // 协议自相矛盾（cached > prompt）：分解出负数 = 数据口径错误，拒收
        OpenAiTokenUsage sample = new OpenAiTokenUsage(50, 100, 80L, null);

        assertThatThrownBy(() -> TokenUsage.fromOpenAiProtocol(sample))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("token 用量不能为负数");
    }

    @Test
    void given_negative_tier_when_construct_then_rejected() {
        assertThatThrownBy(() -> new TokenUsage(-1, 0, 0, 0, 0))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("token 用量不能为负数");
    }

    @Test
    void given_zero_constant_when_total_then_zero() {
        assertThat(TokenUsage.ZERO.total()).isZero();
    }

    @Test
    void given_two_usages_when_plus_then_tierwise_sum() {
        TokenUsage a = new TokenUsage(10, 20, 30, 40, 50);
        TokenUsage b = new TokenUsage(1, 2, 3, 4, 5);

        assertThat(a.plus(b)).isEqualTo(new TokenUsage(11, 22, 33, 44, 55));
        assertThat(a.plus(TokenUsage.ZERO)).isEqualTo(a);
    }
}
