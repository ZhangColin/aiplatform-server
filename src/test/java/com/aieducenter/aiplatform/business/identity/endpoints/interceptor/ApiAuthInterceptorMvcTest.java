package com.aieducenter.aiplatform.business.identity.endpoints.interceptor;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.aieducenter.aiplatform.config.WebMvcConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/**} 鉴权拦截面（A2 §6）：无会话 401 统一信封（经 AuthExceptionHandler，
 * 片0 占位在此接线）、有用户上下文放行、非 /api 路径不拦、ASYNC 再入放行
 * （SSE 语义，见拦截器 javadoc）。走 WebMvcConfig 真实注册路径（含 /api/** 模式）。
 */
@WebMvcTest(controllers = ApiAuthInterceptorMvcTest.ProbeController.class)
@Import({ApiAuthInterceptorMvcTest.ProbeController.class, WebMvcConfig.class})
class ApiAuthInterceptorMvcTest {

    @Autowired
    private MockMvc mockMvc;

    /** 切片会收集 Filter bean（BffSessionContextFilter）但不带其依赖——mock 掉 */
    @MockitoBean
    private BffSessionStore sessionStore;

    @RestController
    static class ProbeController {

        @GetMapping("/api/probe")
        public String apiProbe() {
            return "ok";
        }

        @GetMapping("/public/probe")
        public String publicProbe() {
            return "ok";
        }
    }

    @Test
    void given_no_user_context_when_request_api_then_401_with_unified_envelope() throws Exception {
        mockMvc.perform(get("/api/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void given_session_cookie_when_request_api_then_filter_binds_context_and_allowed() throws Exception {
        // 走真实链路：BffSessionContextFilter 凭 cookie 绑定上下文 → 拦截器放行
        when(sessionStore.get("sid-1")).thenReturn(Optional.of(
                new BffSession(7L, "张三", "idt", "at", "rt",
                        java.time.Instant.now().plusSeconds(3600))));

        mockMvc.perform(get("/api/probe")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-1")))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void given_no_user_context_when_request_non_api_then_not_intercepted() throws Exception {
        // /auth/**、swagger、actuator 不在 /api 下——注册模式天然放行
        mockMvc.perform(get("/public/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void given_async_dispatch_without_context_when_pre_handle_then_allowed() {
        // SSE emitter 完成触发的 ASYNC 再入在另一线程，上下文已不在——不得再拦
        MockHttpServletRequest asyncRequest = new MockHttpServletRequest("GET", "/api/events");
        asyncRequest.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);

        assertThat(new ApiAuthInterceptor().preHandle(asyncRequest, null, null)).isTrue();
    }

    // -------- 测试工具 --------
}
