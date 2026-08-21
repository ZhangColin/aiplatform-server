package com.aieducenter.aiplatform.business.identity.infrastructure.session;

import java.time.Instant;

import com.cartisan.core.context.RequestContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话 → RequestContext 过滤器：有效会话绑定 accountId/显示名、无会话清空用户
 * （含洗掉透传的 X-User-* 头）、会话 id 未知视同无会话。
 */
class BffSessionContextFilterTest {

    private final BffSessionStore sessionStore = new BffSessionStore();
    private final BffSessionContextFilter filter =
            new BffSessionContextFilter(providerOf(sessionStore));

    /** ObjectProvider 替身：getIfAvailable 返回给定存储（null = 切片缺席场景） */
    private static ObjectProvider<BffSessionStore> providerOf(BffSessionStore store) {
        return new ObjectProvider<>() {
            @Override
            public BffSessionStore getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public BffSessionStore getIfAvailable() {
                return store;
            }
        };
    }

    private static ObjectProvider<BffSessionStore> emptyProvider() {
        return providerOf(null);
    }

    @Test
    void given_no_store_in_context_when_filter_then_passthrough_without_rebinding()
            throws Exception {
        // @WebMvcTest 切片：Filter bean 在、会话存储缺席 → 静默放行（不绑不洗）
        BffSessionContextFilter bareFilter = new BffSessionContextFilter(emptyProvider());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workspaces");

        bareFilter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                captured = new Captured(RequestContext.getUserId(), RequestContext.getUserName(),
                        RequestContext.getRequestId()));

        assertThat(captured.userId).isNull();
    }

    @Test
    void given_valid_session_cookie_when_filter_then_user_bound_with_account_id_and_display_name()
            throws Exception {
        sessionStore.put("sid-1",
                new BffSession(42L, "张三", "idt", "at", "rt", Instant.now().plusSeconds(3600)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setCookies(new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-1"));

        runAndCapture(request);

        assertThat(captured.userId).isEqualTo(42L);
        assertThat(captured.userName).isEqualTo("张三");
    }

    @Test
    void given_no_cookie_when_filter_then_user_cleared() throws Exception {
        runAndCapture(new MockHttpServletRequest("GET", "/api/me"));

        assertThat(captured.userId).isNull();
        assertThat(captured.userName).isNull();
    }

    @Test
    void given_unknown_session_id_when_filter_then_user_cleared() throws Exception {
        sessionStore.put("sid-1", session(1L, "张三"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setCookies(new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "other"));

        runAndCapture(request);

        assertThat(captured.userId).isNull();
    }

    @Test
    void given_forged_user_headers_without_session_when_filter_then_not_visible_downstream()
            throws Exception {
        // cartisan RequestContextFilter 会把 X-User-Id/Name 头透传进上下文；
        // 无 BFF 会话时本过滤器必须洗掉——伪造头不得残留在上下文/审计字段
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.addHeader("X-User-Id", "999");
        request.addHeader("X-User-Name", "attacker");

        RequestContext forged = new RequestContext("req-1", "127.0.0.1", null, null,
                999L, "attacker", null, null);
        RequestContext.run(forged, () -> {
            try {
                runAndCapture(request);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(captured.userId).isNull();
        assertThat(captured.userName).isNull();
        assertThat(captured.requestId).isEqualTo("req-1"); // 非用户字段保留
    }

    @Test
    void given_valid_session_over_forged_header_when_filter_then_session_wins() throws Exception {
        sessionStore.put("sid-1", session(42L, "张三"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.addHeader("X-User-Id", "999");
        request.setCookies(new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-1"));

        RequestContext forged = new RequestContext("req-1", "127.0.0.1", null, null,
                999L, "attacker", null, null);
        RequestContext.run(forged, () -> {
            try {
                runAndCapture(request);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(captured.userId).isEqualTo(42L);
        assertThat(captured.userName).isEqualTo("张三");
    }

    // -------- 测试工具 --------

    private record Captured(Long userId, String userName, String requestId) {
    }

    private Captured captured;

    private static BffSession session(Long accountId, String displayName) {
        return new BffSession(accountId, displayName, "idt", "at", "rt",
                Instant.now().plusSeconds(3600));
    }

    /** 过滤后由下游链条读取 RequestContext（模拟 interceptor/controller 视角） */
    private void runAndCapture(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> captured = new Captured(
                RequestContext.getUserId(), RequestContext.getUserName(),
                RequestContext.getRequestId()));
    }
}
