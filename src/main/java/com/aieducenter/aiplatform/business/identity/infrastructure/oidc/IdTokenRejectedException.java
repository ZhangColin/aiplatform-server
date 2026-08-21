package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

/**
 * id_token 校验未通过（A2 §2 表 3：验签 / iss / aud / exp / nonce 任一失败，
 * {@code IDN_002}）。
 *
 * <p>{@code reason} 是排障用的具体原因，只进日志——对外统一由 callback 兜底
 * {@code /?error=exchange_failed}，不漏校验细节。</p>
 */
public class IdTokenRejectedException extends RuntimeException {

    public IdTokenRejectedException(String reason) {
        super(reason);
    }

    public IdTokenRejectedException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
