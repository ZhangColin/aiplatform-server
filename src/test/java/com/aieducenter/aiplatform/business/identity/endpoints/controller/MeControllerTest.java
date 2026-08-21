package com.aieducenter.aiplatform.business.identity.endpoints.controller;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.aieducenter.aiplatform.business.identity.application.MeAppService;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.aieducenter.aiplatform.config.WebMvcConfig;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/me 契约（A2 §3 / 票 #33）：统一信封 data: { accountId, displayName }，
 * accountId 字符串承载（TSID 超出 JS 安全整数）。走真实链路（filter 绑定 → 拦截器
 * → controller → MeAppService），无会话 401 统一信封。
 */
@WebMvcTest(MeController.class)
@Import({MeAppService.class, WebMvcConfig.class})
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BffSessionStore sessionStore;

    @Test
    void given_session_cookie_when_get_me_then_account_id_and_display_name_in_envelope()
            throws Exception {
        when(sessionStore.get("sid-1")).thenReturn(Optional.of(
                new BffSession(3897654321098765432L, "张三", "idt", "at", "rt",
                        Instant.now().plusSeconds(60))));

        mockMvc.perform(get("/api/me")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accountId").value("3897654321098765432"))
                .andExpect(jsonPath("$.data.displayName").value("张三"));
    }

    @Test
    void given_no_session_when_get_me_then_401_with_unified_envelope() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void given_stale_session_id_when_get_me_then_401() throws Exception {
        when(sessionStore.get("gone")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/me")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "gone")))
                .andExpect(status().isUnauthorized());
    }
}
