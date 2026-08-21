package com.aieducenter.aiplatform.business.identity.endpoints.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aieducenter.aiplatform.business.identity.application.AuthAppService;
import com.aieducenter.aiplatform.business.identity.application.AuthAppService.LoginCompletion;
import com.aieducenter.aiplatform.business.identity.application.AuthAppService.LoginRedirect;
import com.aieducenter.aiplatform.business.identity.application.AuthAppService.LogoutCompletion;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;

/**
 * OIDC BFF 认证端点（A2 §2 + #32）：/auth/login 发起、/auth/callback 换 token 建会话、
 * /auth/logout RP-Initiated 登出。302 跳转流（非 ApiResponse 数据端点）；cookie 由
 * 应用层按 {@link AuthCookies} 契约装配（HttpOnly + SameSite=Lax，Secure 随
 * {@code sso.cookie-secure}），本层只落 Set-Cookie 头。
 */
@RestController
@Tag(name = "认证 BFF（OIDC）", description = "登录/登出 302 跳转流（business.identity）")
public class AuthController {

    private final AuthAppService appService;

    public AuthController(AuthAppService appService) {
        this.appService = appService;
    }

    @GetMapping("/auth/login")
    @Operation(summary = "登录发起", description = """
            种 oauth_txn 事务 cookie（state:nonce:returnTo，600s）并 302 到 identity
            /authorize。returnTo 只接受单个 / 开头的同源相对路径（#32，非法回落 /），
            登录成功后回跳该路径。""")
    public ResponseEntity<Void> login(
            @Parameter(description = "登录后回跳路径（同源相对路径，非法回落 /）")
            @RequestParam(value = "returnTo", required = false) String returnTo,
            HttpServletResponse response) {
        LoginRedirect redirect = appService.beginLogin(returnTo);
        response.addHeader(HttpHeaders.SET_COOKIE, redirect.txnCookie().toString());
        return redirect(redirect.authorizeUrl());
    }

    @GetMapping("/auth/callback")
    @Operation(summary = "授权码回调", description = """
            identity 302 落点（经前端 3333 同源代理）。state 与事务 cookie 精确比对 →
            换 token（含 JWKS RS256 验签 + iss/aud/exp/nonce）→ 账号 upsert → 种
            aiplatform_session 并 302 回 returnTo。失败 302 回 /?error=state_mismatch
            或 /?error=exchange_failed（identity 拒绝授权只带 error 不带 code 时同走
            exchange_failed；具体原因只进服务端日志）。""")
    public ResponseEntity<Void> callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @CookieValue(value = AuthCookies.TXN_COOKIE_NAME, required = false) String txn,
            HttpServletResponse response) {
        LoginCompletion completion = appService.completeLogin(code, state, txn);
        if (completion.sessionCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, completion.sessionCookie().toString());
        }
        response.addHeader(HttpHeaders.SET_COOKIE, completion.txnClearCookie().toString());
        return redirect(completion.redirectTo());
    }

    @PostMapping("/auth/logout")
    @Operation(summary = "登出", description = """
            先取 id_token 作 hint 再删本地会话，清 aiplatform_session cookie 并 302 到
            identity RP-Initiated Logout（清 identity 侧 SSO 会话），identity 按
            post_logout_redirect_uri 白名单 302 回前端首页。前端以 form POST 姿态调用。""")
    public ResponseEntity<Void> logout(
            @CookieValue(value = AuthCookies.SESSION_COOKIE_NAME, required = false) String sessionId,
            HttpServletResponse response) {
        LogoutCompletion completion = appService.logout(sessionId);
        response.addHeader(HttpHeaders.SET_COOKIE, completion.sessionClearCookie().toString());
        return redirect(completion.logoutUrl());
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(java.net.URI.create(location)).build();
    }
}
