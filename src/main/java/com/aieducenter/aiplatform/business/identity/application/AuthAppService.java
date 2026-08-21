package com.aieducenter.aiplatform.business.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.error.IdentityMessage;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.domain.model.IdTokenClaims;
import com.aieducenter.aiplatform.business.identity.domain.model.OauthTransaction;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.IdTokenRejectedException;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.OidcClient;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.SsoProperties;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.TokenExchangeException;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.TokenResponse;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;

import lombok.extern.slf4j.Slf4j;

/**
 * OIDC BFF 认证用例（A2 §2 + #32 returnTo 增量）：登录发起 / 回调换 token 建档建会话 /
 * 登出（RP-Initiated）。
 *
 * <p>事务形态：identity 远程调用（换 token / 验签）一律在事务外，账号 upsert 单独收进
 * 短事务（照 WorkspaceLifecycleAppService 模式）。错误姿态照 identity demo：state 不符
 * → {@code /?error=state_mismatch}，换 token / 验签失败 → {@code /?error=exchange_failed}，
 * 具体原因只进日志（不漏内部细节给前端）。</p>
 */
@Service
@Slf4j
public class AuthAppService {

    private final SsoProperties properties;
    private final OidcClient oidcClient;
    private final BffSessionStore sessionStore;
    private final AccountRepository accountRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public AuthAppService(SsoProperties properties, OidcClient oidcClient,
            BffSessionStore sessionStore, AccountRepository accountRepository,
            TransactionTemplate transactionTemplate) {
        this(properties, oidcClient, sessionStore, accountRepository, transactionTemplate,
                Clock.systemUTC());
    }

    AuthAppService(SsoProperties properties, OidcClient oidcClient, BffSessionStore sessionStore,
            AccountRepository accountRepository, TransactionTemplate transactionTemplate,
            Clock clock) {
        this.properties = properties;
        this.oidcClient = oidcClient;
        this.sessionStore = sessionStore;
        this.accountRepository = accountRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 登录发起（#32）：签发事务（state/nonce/净化 returnTo），返回 authorize 跳转与
     * 事务 cookie（Set-Cookie 就绪形态，由 controller 落头）。
     */
    public LoginRedirect beginLogin(String requestedReturnTo) {
        OauthTransaction transaction = OauthTransaction.issue(requestedReturnTo);
        return new LoginRedirect(
                oidcClient.authorizeUrl(transaction.state(), transaction.nonce()),
                txnCookie(transaction.cookieValue(), AuthCookies.TXN_COOKIE_MAX_AGE_SECONDS));
    }

    /**
     * 回调：state 精确比对（防 CSRF）→ 换 token → id_token 全量校验（nonce 防重放）→
     * 账号 upsert → 建会话（token 三件套只存服务端）→ 回跳 returnTo。
     */
    public LoginCompletion completeLogin(String code, String stateParam, String txnCookieValue) {
        Optional<OauthTransaction> parsed = OauthTransaction.parse(txnCookieValue);
        if (parsed.isEmpty() || stateParam == null
                || !parsed.get().state().equals(stateParam)) {
            return failure("error=state_mismatch");
        }
        OauthTransaction transaction = parsed.get();

        // identity 拒绝授权时回跳只带 error 不带 code——同走 exchange_failed 兜底
        if (code == null || code.isBlank()) {
            return failure("error=exchange_failed");
        }

        TokenResponse tokens;
        try {
            tokens = oidcClient.exchangeCode(code);
        } catch (TokenExchangeException e) {
            log.warn("{}：identity /token 换 token 失败，status={}, body={}",
                    IdentityMessage.TOKEN_EXCHANGE_FAILED.code(),
                    e.httpStatus(), e.responseBody());
            return failure("error=exchange_failed");
        }

        IdTokenClaims claims;
        try {
            claims = oidcClient.verifyIdToken(tokens.idToken(), transaction.nonce());
        } catch (IdTokenRejectedException e) {
            log.warn("{}：{}", IdentityMessage.ID_TOKEN_REJECTED.code(), e.getMessage());
            return failure("error=exchange_failed");
        }

        Account account = transactionTemplate.execute(status -> upsertAccount(claims));

        String sessionId = OauthTransaction.randomToken();
        Instant expiresAt = tokens.expiresIn() != null
                ? clock.instant().plusSeconds(tokens.expiresIn())
                : null;
        sessionStore.put(sessionId, new BffSession(
                account.getId(), claims.displayName(),
                tokens.idToken(), tokens.accessToken(), tokens.refreshToken(), expiresAt));

        return new LoginCompletion(
                properties.getAppBaseUrl() + transaction.returnTo(),
                sessionCookie(sessionId).build(), txnCookie("", 0));
    }

    /** 回调失败统一兜底：回前端错误页 + 清事务 cookie（具体原因已在日志里） */
    private LoginCompletion failure(String errorQuery) {
        return new LoginCompletion(properties.getAppBaseUrl() + "/?" + errorQuery,
                null, txnCookie("", 0));
    }

    /**
     * 登出：先取 id_token 作 hint（删了就取不到，顺序不能反——identity 凭 hint 的 sub
     * 在 SSO cookie 丢失时兜底定位会话，demo #40/#45）再删本地会话，返回 identity
     * RP-Initiated Logout 跳转。
     */
    public LogoutCompletion logout(String sessionId) {
        String idTokenHint = sessionStore.get(sessionId)
                .map(BffSession::idToken)
                .orElse(null);
        sessionStore.remove(sessionId);

        String base = properties.getAppBaseUrl();
        String postLogoutRedirectUri = base.endsWith("/") ? base : base + "/";
        return new LogoutCompletion(
                oidcClient.logoutUrl(postLogoutRedirectUri, OauthTransaction.randomToken(),
                        idTokenHint),
                sessionCookie("").maxAge(0).build());
    }

    // -------- cookie 装配（契约名/生命周期见 AuthCookies；Secure 随 sso.cookie-secure） --------

    private ResponseCookie txnCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(AuthCookies.TXN_COOKIE_NAME, value)
                .httpOnly(true).secure(properties.isCookieSecure()).sameSite("Lax")
                .path("/").maxAge(maxAgeSeconds)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder sessionCookie(String value) {
        return ResponseCookie.from(AuthCookies.SESSION_COOKIE_NAME, value)
                .httpOnly(true).secure(properties.isCookieSecure()).sameSite("Lax")
                .path("/");
    }

    /**
     * 账号 upsert（A2 §3）：按 external_id 找，无则建，显示名有变则更新。
     * 并发同 sub 首登撞唯一索引的窄路径由 uk_idn_accounts_external_id 兜底（罕见，v1 不重试）。
     */
    private Account upsertAccount(IdTokenClaims claims) {
        return accountRepository.findByExternalId(claims.subject())
                .map(existing -> {
                    if (existing.syncDisplayName(claims.displayName())) {
                        return accountRepository.save(existing);
                    }
                    return existing;
                })
                .orElseGet(() -> accountRepository.save(
                        Account.register(claims.subject(), claims.displayName())));
    }

    /** 登录发起结果：authorize 跳转 URL + 事务 cookie（Set-Cookie 就绪形态） */
    public record LoginRedirect(String authorizeUrl, ResponseCookie txnCookie) {
    }

    /**
     * 回调结果：redirectTo 为绝对跳转（成功 = appBaseUrl+returnTo；失败 = 错误页查询参数）；
     * sessionCookie 非空表示会话建立（controller 据此 Set-Cookie）；txnClearCookie 恒有
     * （事务 cookie 一次性，成功失败皆清——比 identity demo 多清 state_mismatch 场景）。
     */
    public record LoginCompletion(String redirectTo, ResponseCookie sessionCookie,
            ResponseCookie txnClearCookie) {
    }

    /** 登出结果：identity /logout 跳转 URL + 业务 cookie 清除指令（Set-Cookie 就绪形态） */
    public record LogoutCompletion(String logoutUrl, ResponseCookie sessionClearCookie) {
    }
}
