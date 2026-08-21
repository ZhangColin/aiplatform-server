package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

/**
 * 用 code 换 token 失败（identity {@code /token} 非 2xx，或连接/超时/响应解析失败，
 * {@code IDN_003}）。照 identity demo：细节带出来只供 BFF 记日志排查（demo issue #35
 * 教训），对外统一 {@code /?error=exchange_failed}。
 */
public class TokenExchangeException extends RuntimeException {

    private final Integer httpStatus;
    private final String responseBody;

    public TokenExchangeException(String message, Integer httpStatus, String responseBody,
            Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    /** identity {@code /token} 的 HTTP 状态码；连接/超时/解析失败等无状态码时为 {@code null} */
    public Integer httpStatus() {
        return httpStatus;
    }

    /** identity {@code /token} 的响应体（通常为 {@code {error, error_description}}）；无则为 {@code null} */
    public String responseBody() {
        return responseBody;
    }
}
