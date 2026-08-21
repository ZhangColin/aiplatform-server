package com.aieducenter.aiplatform.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.cartisan.core.exception.CodeMessage;
import com.cartisan.web.response.ApiResponse;

/**
 * 认证/授权异常全局映射（401/403，片0 占位）。
 *
 * <p>cartisan-security 的 SecurityExceptionHandler 随依赖一并移除（ADR-0001），
 * 本类顶上其位置：{@link AuthException} → HTTP 状态 + 统一响应体。
 * 抛出方（BFF 会话过滤器）随 A2 identity 票接线。</p>
 */
@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthException(AuthException exception) {
        CodeMessage codeMessage = exception.codeMessage();
        return ResponseEntity
                .status(codeMessage.httpStatus())
                .body(ApiResponse.error(codeMessage));
    }
}
