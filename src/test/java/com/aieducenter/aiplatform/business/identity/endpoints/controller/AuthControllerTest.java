package com.aieducenter.aiplatform.business.identity.endpoints.controller;

import java.util.Collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aieducenter.aiplatform.business.identity.application.AuthAppService;
import com.aieducenter.aiplatform.business.identity.application.AuthAppService.LoginCompletion;
import com.aieducenter.aiplatform.business.identity.application.AuthAppService.LoginRedirect;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证三端点 cookie/302 语义（A2 §2 表 2/8）：oauth_txn 事务 cookie 生命周期、
 * aiplatform_session 种/清、Secure 标志随 sso.cookie-secure（local=false）。
 */
class AuthControllerTest {

    private static final String APP_BASE = "http://localhost:3333";

    private AuthAppService appService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        appService = org.mockito.Mockito.mock(AuthAppService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(appService)).build();
    }

    private static ResponseCookie cookie(String name, String value, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(true).sameSite("Lax").path("/").maxAge(maxAgeSeconds)
                .build();
    }

    @Test
    void given_login_request_when_login_then_txn_cookie_set_and_302_to_authorize() throws Exception {
        when(appService.beginLogin("/workbench")).thenReturn(
                new LoginRedirect("http://identity.localhost:10001/authorize?state=st",
                        cookie(AuthCookies.TXN_COOKIE_NAME, "st:nc:%2F", 600)));

        mockMvc.perform(get("/auth/login").queryParam("returnTo", "/workbench"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://identity.localhost:10001/authorize?state=st"))
                .andExpect(result -> assertThat(
                        result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                        .singleElement()
                        .satisfies(cookie -> assertThat(cookie)
                                .startsWith("oauth_txn=st:nc:%2F;")
                                .contains("Path=/")
                                .contains("Max-Age=600")
                                .contains("HttpOnly")
                                .contains("SameSite=Lax")));
    }

    @Test
    void given_completed_login_when_callback_then_session_cookie_set_and_txn_cleared()
            throws Exception {
        when(appService.completeLogin(any(), any(), any())).thenReturn(
                new LoginCompletion(APP_BASE + "/workbench",
                        cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-9", -1),
                        cookie(AuthCookies.TXN_COOKIE_NAME, "", 0)));

        mockMvc.perform(get("/auth/callback").queryParam("code", "c").queryParam("state", "st"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(APP_BASE + "/workbench"))
                .andExpect(this::assertSessionCookiePlanted)
                .andExpect(this::assertTxnCookieCleared);
    }

    @Test
    void given_failed_login_when_callback_then_no_session_cookie_but_txn_cleared() throws Exception {
        when(appService.completeLogin(any(), any(), any())).thenReturn(
                new LoginCompletion(APP_BASE + "/?error=state_mismatch", null,
                        cookie(AuthCookies.TXN_COOKIE_NAME, "", 0)));

        mockMvc.perform(get("/auth/callback").queryParam("code", "c").queryParam("state", "st"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(APP_BASE + "/?error=state_mismatch"))
                .andExpect(result -> assertThat(
                        cookieValues(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE),
                                AuthCookies.SESSION_COOKIE_NAME)).isEmpty())
                .andExpect(this::assertTxnCookieCleared);
    }

    @Test
    void given_logout_request_when_logout_then_session_cookie_cleared_and_302() throws Exception {
        when(appService.logout("sid-9")).thenReturn(
                new AuthAppService.LogoutCompletion("http://identity.localhost:10001/logout?x",
                        cookie(AuthCookies.SESSION_COOKIE_NAME, "", 0)));

        mockMvc.perform(post("/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-9")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://identity.localhost:10001/logout?x"))
                .andExpect(result -> {
                    Collection<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
                    assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
                            .startsWith("aiplatform_session=;")
                            .contains("Max-Age=0")
                            .contains("HttpOnly")
                            .contains("SameSite=Lax"));
                });
    }

    @Test
    void given_missing_txn_cookie_when_callback_then_app_service_invoked_with_null_txn()
            throws Exception {
        when(appService.completeLogin(any(), any(), any())).thenReturn(
                new LoginCompletion(APP_BASE + "/?error=state_mismatch", null,
                        cookie(AuthCookies.TXN_COOKIE_NAME, "", 0)));

        mockMvc.perform(get("/auth/callback").queryParam("code", "c").queryParam("state", "st"))
                .andExpect(status().isFound());

        verify(appService).completeLogin("c", "st", null);
    }

    // -------- 断言工具 --------

    private void assertSessionCookiePlanted(org.springframework.test.web.servlet.MvcResult result) {
        assertThat(cookieValues(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE),
                AuthCookies.SESSION_COOKIE_NAME))
                .singleElement()
                .satisfies(value -> assertThat(value)
                        .startsWith("aiplatform_session=sid-9;")
                        .doesNotContain("Max-Age="));
    }

    private void assertTxnCookieCleared(org.springframework.test.web.servlet.MvcResult result) {
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(cookie -> assertThat(cookie)
                        .startsWith("oauth_txn=;")
                        .contains("Max-Age=0"));
    }

    private static java.util.List<String> cookieValues(Collection<String> setCookieHeaders,
            String name) {
        return setCookieHeaders.stream()
                .filter(header -> header.startsWith(name + "="))
                .toList();
    }
}
