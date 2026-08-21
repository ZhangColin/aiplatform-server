package com.aieducenter.aiplatform.business.identity.infrastructure.session;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cartisan.core.context.RequestContext;

import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;

/**
 * 会话 → RequestContext 过滤器（A2 §2 表 6，ADR-0001：顶替 cartisan-security
 * SecurityFilter 的位置，业务代码读法不变）。
 *
 * <p>cookie → 会话 → {@code userId = accountId}（内部 PK，非 sub）、
 * {@code userName = 显示名}；无有效会话时显式清空用户字段——顺带洗掉请求头携带的
 * {@code X-User-Id}/{@code X-User-Name}（cartisan RequestContextFilter 会透传机机
 * 调用的头；v1 用户面全部以 BFF 会话为准，伪造头不得残留进上下文/审计字段）。</p>
 *
 * <p>顺序：紧跟 cartisan RequestContextFilter（{@code MIN_VALUE}，绑定 requestId/
 * clientIp 与 ScopedValue 骨架）之后；本过滤器内用 {@code withUser} 嵌套重绑，
 * 保留 requestId/clientIp。{@code /api/**} 的强制拦截不在本层（filter 抛的异常进不了
 * 全局异常映射），归 {@code ApiAuthInterceptor}。</p>
 *
 * <p>会话存储经 {@link ObjectProvider} 注入：@WebMvcTest 切片会收 Filter bean 但不含
 * identity BC 的存储——整个认证机制在切片中缺席时本过滤器静默放行（切片要测的是
 * 单个 controller 的契约，鉴权行为归 identity BC 自己的测试）。</p>
 */
@Component
public class BffSessionContextFilter extends OncePerRequestFilter implements Ordered {

    /** cartisan RequestContextFilter = MIN_VALUE；本过滤器紧随其后 */
    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    /** cartisan 过滤器缺席（窄测试上下文等）时的兜底骨架上下文 */
    private static final RequestContext BARE =
            new RequestContext(null, null, null, null, null, null, null, null);

    private final ObjectProvider<BffSessionStore> sessionStoreProvider;

    public BffSessionContextFilter(ObjectProvider<BffSessionStore> sessionStoreProvider) {
        this.sessionStoreProvider = sessionStoreProvider;
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        BffSessionStore sessionStore = sessionStoreProvider.getIfAvailable();
        if (sessionStore == null) {
            chain.doFilter(request, response);
            return;
        }
        BffSession session = sessionStore.get(cookieValue(request, AuthCookies.SESSION_COOKIE_NAME))
                .orElse(null);
        RequestContext current = RequestContext.CONTEXT.isBound()
                ? RequestContext.CONTEXT.get()
                : BARE;
        RequestContext bound = session != null
                ? current.withUser(session.accountId(), session.displayName())
                : current.withUser(null, null);
        try {
            RequestContext.runFor(bound, () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // runFor 声明 throws Exception；上面的具名分支已覆盖实际可抛集合
            throw new ServletException(e);
        }
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
