package com.aieducenter.aiplatform.business.identity.domain.model;

/**
 * 验签通过后的 id_token 用户声明（A2 §3）。
 *
 * <p>只取建档与展示需要的四项（框架类型 nimbus JWTClaimsSet 留在基础设施层，
 * 领域零框架依赖）；显示名推导链见 {@link #displayName()}。</p>
 */
public record IdTokenClaims(String subject, String nickname, String name, String preferredUsername) {

    public IdTokenClaims {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("id_token 缺少 sub");
        }
    }

    /**
     * 显示名（A2 §3 取值链）：nickname → name → preferred_username → sub 兜底。
     */
    public String displayName() {
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }
        return subject;
    }
}
