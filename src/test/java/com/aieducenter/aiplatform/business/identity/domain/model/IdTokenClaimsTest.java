package com.aieducenter.aiplatform.business.identity.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * id_token 用户声明（A2 §3）：显示名推导链 nickname → name → preferred_username → sub 兜底。
 */
class IdTokenClaimsTest {

    @Test
    void given_nickname_present_when_display_name_then_nickname_wins() {
        assertThat(new IdTokenClaims("sub-1", "nick", "full name", "preferred").displayName())
                .isEqualTo("nick");
    }

    @Test
    void given_nickname_blank_when_display_name_then_name_next() {
        assertThat(new IdTokenClaims("sub-1", "", "full name", "preferred").displayName())
                .isEqualTo("full name");
    }

    @Test
    void given_nickname_and_name_blank_when_display_name_then_preferred_username() {
        assertThat(new IdTokenClaims("sub-1", null, "  ", "preferred").displayName())
                .isEqualTo("preferred");
    }

    @Test
    void given_all_profile_claims_blank_when_display_name_then_subject_fallback() {
        assertThat(new IdTokenClaims("sub-1", null, null, null).displayName()).isEqualTo("sub-1");
    }

    @Test
    void given_blank_subject_when_construct_then_rejected() {
        assertThatThrownBy(() -> new IdTokenClaims(" ", "nick", null, null))
                .isInstanceOf(Exception.class);
    }
}
