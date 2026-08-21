package com.aieducenter.aiplatform.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.cartisan.core.exception.BaseCodeMessage;
import com.cartisan.web.response.ApiResponse;

/**
 * 静态资源未命中全局映射（404，片0）。
 *
 * <p>cartisan-web 的 GlobalExceptionHandler 只映射了 {@code NoHandlerFoundException}
 * （旧机制，Boot 3 默认不抛）；Boot 3.2+ 静态资源未命中抛的是
 * {@link NoResourceFoundException}，会落进兜底 handler 变 500——本类恢复 404 语义，
 * 未知路径不再伪装成服务端错误。通用错误码复用 {@link BaseCodeMessage}，不自造。</p>
 */
@RestControllerAdvice
public class NotFoundExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity
                .status(BaseCodeMessage.NOT_FOUND.httpStatus())
                .body(ApiResponse.error(BaseCodeMessage.NOT_FOUND));
    }
}
