package com.aieducenter.aiplatform.base.chatagent.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aieducenter.aiplatform.base.chatagent.domain.error.ChatAgentMessage;
import com.cartisan.core.exception.DomainException;
import org.junit.jupiter.api.Test;

/**
 * {@link ModelRef} 模型串解析（#44：provider 白名单暂仅 deepseek，后续逐个放开）。
 */
class ModelRefTest {

    @Test
    void given_deepseek_model_string_when_parse_then_provider_and_model_id_split() {
        ModelRef ref = ModelRef.parse("deepseek:deepseek-v4-flash");

        assertThat(ref.provider()).isEqualTo("deepseek");
        assertThat(ref.modelId()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void given_gemini_model_string_when_parse_then_rejected_as_unsupported() {
        assertThatThrownBy(() -> ModelRef.parse("gemini:gemini-2.0-flash"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ChatAgentMessage.MODEL_REF_INVALID.message());
    }

    @Test
    void given_blank_model_string_when_parse_then_rejected() {
        assertThatThrownBy(() -> ModelRef.parse(" "))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_string_without_colon_when_parse_then_rejected() {
        assertThatThrownBy(() -> ModelRef.parse("deepseek-v4-flash"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_blank_model_id_when_parse_then_rejected() {
        assertThatThrownBy(() -> ModelRef.parse("deepseek:"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_string_with_multiple_colons_when_parse_then_rejected() {
        assertThatThrownBy(() -> ModelRef.parse("deepseek:deepseek-chat:extra"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_model_ref_when_to_model_string_then_original_recomposed() {
        String original = "deepseek:deepseek-v4-flash";

        assertThat(ModelRef.parse(original).toModelString()).isEqualTo(original);
    }
}
