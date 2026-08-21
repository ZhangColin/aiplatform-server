package com.aieducenter.aiplatform.business.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.business.identity.application.AuthAppService.LoginCompletion;
import com.aieducenter.aiplatform.business.identity.application.AuthAppService.LoginRedirect;
import com.aieducenter.aiplatform.business.identity.application.AuthAppService.LogoutCompletion;
import org.springframework.http.ResponseCookie;

import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.model.IdTokenClaims;
import com.aieducenter.aiplatform.business.identity.domain.model.OauthTransaction;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.IdTokenRejectedException;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.OidcClient;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.TokenExchangeException;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.TokenResponse;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OIDC BFF 认证用例（票 #19 验收口径）：mock OIDC 客户端（远程调用），真 PG 验
 * 建档 upsert（同 sub 不重复建档、显示名变更更新）、会话建立与登出时序（先取 hint
 * 再删会话）。sub 每次运行随机，避免历史数据撞唯一索引。
 */
@SpringBootTest(properties = {
        "sso.issuer=http://identity.localhost:10001",
        "sso.client-id=test-client",
        "sso.client-secret=test-secret",
        "sso.redirect-uri=http://localhost:3333/auth/callback",
        "sso.app-base-url=http://localhost:3333",
        "sso.cookie-secure=false"})
class AuthAppServiceTest {

    private static final String APP_BASE = "http://localhost:3333";

    @Autowired
    private AuthAppService appService;

    @Autowired
    private BffSessionStore sessionStore;

    @Autowired
    private AccountRepository accountRepository;

    @MockitoBean
    private OidcClient oidcClient;

    /** 每次运行唯一，规避库中历史行 */
    private final String sub = "sub-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        accountRepository.findByExternalId(sub)
                .ifPresent(account -> accountRepository.deleteAll(List.of(account)));
    }

    // -------- 登录发起（#32 returnTo） --------

    @Test
    void given_login_request_when_begin_then_authorize_url_and_txn_cookie_issued() {
        when(oidcClient.authorizeUrl(anyString(), anyString()))
                .thenReturn("http://identity.localhost:10001/authorize?state=st&nonce=nc");

        LoginRedirect redirect = appService.beginLogin("/workbench?x=1");

        assertThat(redirect.authorizeUrl())
                .isEqualTo("http://identity.localhost:10001/authorize?state=st&nonce=nc");
        OauthTransaction parsed = OauthTransaction.parse(redirect.txnCookie().getValue())
                .orElseThrow();
        assertThat(parsed.returnTo()).isEqualTo("/workbench?x=1");
        assertThat(redirect.txnCookie().isHttpOnly()).isTrue();
        assertThat(redirect.txnCookie().getMaxAge().toSeconds())
                .isEqualTo(600);
    }

    @Test
    void given_unsafe_return_to_when_begin_then_sanitized_to_root() {
        LoginRedirect redirect = appService.beginLogin("//evil.example.com");

        assertThat(OauthTransaction.parse(redirect.txnCookie().getValue()))
                .hasValueSatisfying(txn -> assertThat(txn.returnTo()).isEqualTo("/"));
    }

    // -------- 回调：成功链（换 token → 验签 → 建档 → 建会话 → returnTo 回跳） --------

    @Test
    void given_valid_callback_when_complete_then_account_created_session_built_and_return_to() {
        OauthTransaction txn = OauthTransaction.issue("/workbench");
        stubExchangeAndVerify(txn.nonce(), claims("张三"));

        LoginCompletion completion = appService.completeLogin("code-1", txn.state(),
                txn.cookieValue());

        assertThat(completion.sessionCookie()).isNotNull();
        assertThat(completion.redirectTo()).isEqualTo(APP_BASE + "/workbench");

        Account account = accountRepository.findByExternalId(sub).orElseThrow();
        assertThat(account.getDisplayName()).isEqualTo("张三");

        BffSession session = sessionStore.get(completion.sessionCookie().getValue()).orElseThrow();
        assertThat(session.accountId()).isEqualTo(account.getId());
        assertThat(session.displayName()).isEqualTo("张三");
        assertThat(session.idToken()).isEqualTo("idt-value");
        assertThat(session.accessToken()).isEqualTo("at-value");
        assertThat(session.refreshToken()).isEqualTo("rt-value");
        assertThat(session.expiresAt())
                .isAfter(Instant.now().plusSeconds(3500))
                .isBefore(Instant.now().plusSeconds(3700));
    }

    @Test
    void given_same_sub_second_login_when_complete_then_no_duplicate_and_display_name_updated() {
        OauthTransaction first = OauthTransaction.issue("/");
        stubExchangeAndVerify(first.nonce(), claims("张三"));
        LoginCompletion firstCompletion =
                appService.completeLogin("code-1", first.state(), first.cookieValue());
        Long firstAccountId = accountRepository.findByExternalId(sub).orElseThrow().getId();

        OauthTransaction second = OauthTransaction.issue("/next");
        stubExchangeAndVerify(second.nonce(), claims("李四"));
        LoginCompletion secondCompletion =
                appService.completeLogin("code-2", second.state(), second.cookieValue());

        assertThat(secondCompletion.sessionCookie()).isNotNull();
        assertThat(secondCompletion.redirectTo()).isEqualTo(APP_BASE + "/next");
        // 同 sub 不重复建档：id 不变，显示名更新
        Account account = accountRepository.findByExternalId(sub).orElseThrow();
        assertThat(account.getId()).isEqualTo(firstAccountId);
        assertThat(account.getDisplayName()).isEqualTo("李四");
        // 两次登录两个独立会话
        assertThat(secondCompletion.sessionCookie().getValue())
                .isNotEqualTo(firstCompletion.sessionCookie().getValue());
    }

    // -------- 回调：失败链（细节只进日志，统一兜底错误页） --------

    @Test
    void given_no_txn_cookie_when_complete_then_state_mismatch_and_no_session() {
        LoginCompletion completion = appService.completeLogin("code-1", "some-state", null);

        assertThat(completion.redirectTo()).isEqualTo(APP_BASE + "/?error=state_mismatch");
        assertThat(completion.sessionCookie()).isNull();
        assertThat(accountRepository.findByExternalId(sub)).isEmpty();
    }

    @Test
    void given_state_param_differs_from_cookie_when_complete_then_state_mismatch() {
        OauthTransaction txn = OauthTransaction.issue("/");

        LoginCompletion completion = appService.completeLogin("code-1", "attacker-state",
                txn.cookieValue());

        assertThat(completion.redirectTo()).isEqualTo(APP_BASE + "/?error=state_mismatch");
        assertThat(completion.sessionCookie()).isNull();
    }

    @Test
    void given_identity_denies_authorization_without_code_when_complete_then_exchange_failed() {
        // identity 拒绝授权时回跳只带 error 不带 code——不裸 400，同走兜底错误页
        OauthTransaction txn = OauthTransaction.issue("/");

        LoginCompletion completion = appService.completeLogin(null, txn.state(),
                txn.cookieValue());

        assertThat(completion.redirectTo()).isEqualTo(APP_BASE + "/?error=exchange_failed");
        assertThat(completion.sessionCookie()).isNull();
    }

    @Test
    void given_identity_rejects_code_when_complete_then_exchange_failed() {
        OauthTransaction txn = OauthTransaction.issue("/");
        when(oidcClient.exchangeCode("bad-code")).thenThrow(
                new TokenExchangeException("identity /token 返回 400", 400,
                        "{\"error\":\"invalid_grant\"}", null));

        LoginCompletion completion = appService.completeLogin("bad-code", txn.state(),
                txn.cookieValue());

        assertThat(completion.redirectTo()).isEqualTo(APP_BASE + "/?error=exchange_failed");
        assertThat(completion.sessionCookie()).isNull();
        assertThat(accountRepository.findByExternalId(sub)).isEmpty();
    }

    @Test
    void given_id_token_verification_rejected_when_complete_then_exchange_failed_no_account() {
        OauthTransaction txn = OauthTransaction.issue("/");
        when(oidcClient.exchangeCode("code-1")).thenReturn(
                new TokenResponse("at", "Bearer", 3600L, "rt", "forged-idt"));
        when(oidcClient.verifyIdToken("forged-idt", txn.nonce()))
                .thenThrow(new IdTokenRejectedException("RS256 验签失败"));

        LoginCompletion completion = appService.completeLogin("code-1", txn.state(),
                txn.cookieValue());

        assertThat(completion.redirectTo()).isEqualTo(APP_BASE + "/?error=exchange_failed");
        assertThat(completion.sessionCookie()).isNull();
        // 验签不过不建档（sub 未受信任）
        assertThat(accountRepository.findByExternalId(sub)).isEmpty();
    }

    // -------- 登出（先取 hint 再删会话；post_logout_redirect_uri 独立白名单值） --------

    @Test
    void given_live_session_when_logout_then_hint_taken_before_removal_and_session_gone() {
        sessionStore.put("sid-1", new BffSession(1L, "张三", "idt-hint", "at", "rt",
                Instant.now().plusSeconds(60)));
        when(oidcClient.logoutUrl(anyString(), anyString(), eq("idt-hint")))
                .thenReturn("http://identity.localhost:10001/logout?...");

        LogoutCompletion completion = appService.logout("sid-1");

        assertThat(completion.logoutUrl()).startsWith("http://identity.localhost:10001/logout");
        assertThat(sessionStore.get("sid-1")).isEmpty();

        ArgumentCaptor<String> postLogoutUri = ArgumentCaptor.forClass(String.class);
        verify(oidcClient).logoutUrl(postLogoutUri.capture(), anyString(), eq("idt-hint"));
        // post_logout_redirect_uri = appBaseUrl 规范化补斜杠（独立白名单登记值）
        assertThat(postLogoutUri.getValue()).isEqualTo(APP_BASE + "/");
    }

    @Test
    void given_no_session_when_logout_then_no_hint_but_still_redirects() {
        when(oidcClient.logoutUrl(anyString(), anyString(), isNull()))
                .thenReturn("http://identity.localhost:10001/logout?...");

        LogoutCompletion completion = appService.logout("unknown-sid");

        assertThat(completion.logoutUrl()).startsWith("http://identity.localhost:10001/logout");
        verify(oidcClient).logoutUrl(eq(APP_BASE + "/"), anyString(), isNull());
    }

    // -------- 测试工具 --------

    private IdTokenClaims claims(String nickname) {
        return new IdTokenClaims(sub, nickname, null, null);
    }

    private void stubExchangeAndVerify(String nonce, IdTokenClaims claims) {
        when(oidcClient.exchangeCode(anyString())).thenReturn(
                new TokenResponse("at-value", "Bearer", 3600L, "rt-value", "idt-value"));
        when(oidcClient.verifyIdToken("idt-value", nonce)).thenReturn(claims);
    }
}
