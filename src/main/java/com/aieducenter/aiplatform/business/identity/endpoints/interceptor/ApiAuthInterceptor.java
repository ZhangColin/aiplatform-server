package com.aieducenter.aiplatform.business.identity.endpoints.interceptor;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import com.cartisan.core.context.RequestContext;

import com.aieducenter.aiplatform.web.AuthException;

/**
 * {@code /api/**} 鉴权拦截器（A2 §6：全拦截面含 SSE 双通道，无会话 401）。
 *
 * <p>白名单（{@code /auth/**}、{@code /v3/api-docs/**}、{@code /swagger-ui/**}、
 * actuator health）不落在 {@code /api/**} 下——按路径模式注册即天然放行。用户
 * 上下文由 {@code BffSessionContextFilter} 绑定（含无会话时清空伪造头），本层只判
 * 定有无。401 经 {@code AuthException} 走既有全局映射（片0 占位接线即此）。</p>
 *
 * <p>异步再入放行：SSE 端点（SseEmitter）完成/超时触发的 ASYNC dispatch 在另一
 * 线程，ScopedValue 上下文已不在——首次 REQUEST dispatch 已完成鉴权，此处不再拦。</p>
 */
public class ApiAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        if (RequestContext.getUserId() == null) {
            throw AuthException.unauthorized();
        }
        return true;
    }
}
