package com.aieducenter.aiplatform.web;

import com.cartisan.core.exception.BaseCodeMessage;
import com.cartisan.core.exception.CodeMessage;

/**
 * 认证/授权异常（401/403 全局映射占位，片0）。
 *
 * <p>本服务不引入 cartisan-security（ADR-0001）：A2 的 identity BFF 会话过滤器
 * 判定「未登录 / 无权限」时抛出本异常，由
 * {@link AuthExceptionHandler} 统一映射为 401/403。在此之前它是占位——
 * 通用 HTTP 错误复用 {@link BaseCodeMessage}，不自造。</p>
 */
public class AuthException extends RuntimeException {

    private final CodeMessage codeMessage;

    private AuthException(CodeMessage codeMessage) {
        super(codeMessage.message());
        this.codeMessage = codeMessage;
    }

    /**
     * 未登录 / 会话过期（HTTP 401）。
     */
    public static AuthException unauthorized() {
        return new AuthException(BaseCodeMessage.UNAUTHORIZED);
    }

    /**
     * 已登录但无权限（HTTP 403）。
     */
    public static AuthException forbidden() {
        return new AuthException(BaseCodeMessage.FORBIDDEN);
    }

    public CodeMessage codeMessage() {
        return codeMessage;
    }
}
