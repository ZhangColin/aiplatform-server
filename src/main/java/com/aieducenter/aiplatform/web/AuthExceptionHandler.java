package com.aieducenter.aiplatform.web;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.cartisan.core.exception.CodeMessage;
import com.cartisan.web.response.ApiResponse;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 认证/授权异常全局映射（401/403，片0 落位、A2 接线）。
 *
 * <p>cartisan-security 的 SecurityExceptionHandler 随依赖一并移除（ADR-0001），
 * 本类顶上其位置：{@link AuthException} → HTTP 状态 + 统一响应体。
 * 抛出方：business.identity 的 ApiAuthInterceptor（/api/** 无会话 → 401）。</p>
 *
 * <p>直接写响应（不经消息转换协商）：SSE 端点（{@code Accept: text/event-stream}）
 * 无会话时协商渲染不出 JSON 信封会 500——手写保证任何 Accept 下 401 语义与信封
 * 字节一致。</p>
 */
@RestControllerAdvice
public class AuthExceptionHandler {

    private final ObjectMapper objectMapper;

    public AuthExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(AuthException.class)
    public void handleAuthException(AuthException exception, HttpServletResponse response)
            throws IOException {
        CodeMessage codeMessage = exception.codeMessage();
        response.setStatus(codeMessage.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(codeMessage));
    }
}
