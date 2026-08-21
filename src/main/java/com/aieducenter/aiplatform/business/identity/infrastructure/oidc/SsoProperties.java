package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SSO/OIDC 消费方配置（{@code sso.*} 七项，A2 §2 表 7，照 identity demo 的
 * {@code SsoProperties}）。
 *
 * <p>凭据（clientId/clientSecret）只落本地环境变量，不进 git（A2 §2 表 7）。</p>
 */
@Component
@ConfigurationProperties(prefix = "sso")
public class SsoProperties {

    /** identity IdP 地址（local：http://identity.localhost:10001，须与 discovery 自述 issuer 逐字一致） */
    private String issuer;

    /** 消费方 client_id（app-registry 发放） */
    private String clientId;

    /** 消费方 client_secret（BFF 服务端用，浏览器永不接触） */
    private String clientSecret;

    /** 回调地址（浏览器入口 3333，经 Next 代理到本服务；须与 identity 白名单逐字一致） */
    private String redirectUri;

    /** 授权范围（A2 §7 定稿） */
    private String scope = "openid profile email";

    /** 前端地址（登录/登出后回跳锚点） */
    private String appBaseUrl;

    /**
     * oauth_txn / aiplatform_session cookie 是否标记 Secure。
     *
     * <p>local 跑 {@code http://localhost} 时 Safari/Firefox 不豁免 Secure-over-http，
     * Secure cookie 被拒存 → oauth_txn 落不了地 → callback 恒 state_mismatch（demo
     * issue #37 踩坑）；故 local 显式 false，生产 https 必须 true。</p>
     */
    private boolean cookieSecure = false;

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getAppBaseUrl() { return appBaseUrl; }
    public void setAppBaseUrl(String appBaseUrl) { this.appBaseUrl = appBaseUrl; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
}
