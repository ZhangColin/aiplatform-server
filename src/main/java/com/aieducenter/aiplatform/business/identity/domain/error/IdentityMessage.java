package com.aieducenter.aiplatform.business.identity.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * business.identity 错误定义（前缀 IDN_，ADR-0001 注册表）。
 *
 * <p>认证流程错误（换 token / 验签失败）不直接以 REST 错误暴露——callback 统一
 * 302 回 {@code /?error=exchange_failed}，具体原因（本枚举 code）只进日志（照
 * identity demo 姿态，不漏内部细节给前端）。</p>
 */
public enum IdentityMessage implements CodeMessage {

    ACCOUNT_FIELDS_INCOMPLETE(400, "IDN_001", "账号字段不完整"),

    ID_TOKEN_REJECTED(401, "IDN_002", "id_token 校验未通过（验签 / iss / aud / exp / nonce）"),

    TOKEN_EXCHANGE_FAILED(502, "IDN_003", "identity 令牌端点调用失败");

    private final int httpStatus;
    private final String code;
    private final String message;

    IdentityMessage(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
