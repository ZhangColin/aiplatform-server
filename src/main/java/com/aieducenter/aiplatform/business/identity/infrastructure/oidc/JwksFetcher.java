package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

import com.nimbusds.jose.jwk.JWKSet;

/**
 * JWKS 拉取端口（HTTP 细节留在实现侧，测试注入内存版）。
 */
public interface JwksFetcher {

    /**
     * 拉取 identity {@code /jwks} 全量公钥集；失败抛运行时异常（由调用方决定兜底）。
     */
    JWKSet fetch();
}
