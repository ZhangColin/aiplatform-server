package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * identity {@code /token} 响应（OIDC 标准 snake_case；显式映射，与全局命名策略无关）。
 */
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("id_token") String idToken) {
}
