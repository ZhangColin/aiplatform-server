package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.Builder;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.aieducenter.aiplatform.business.identity.domain.model.IdTokenClaims;

/**
 * OIDC 客户端核心（照 identity demo 形态）：构造 {@code /authorize} 与 RP-Initiated
 * Logout 跳转 URL、code 换 token（服务端带 client_secret，token_endpoint_auth =
 * client_secret_post，form body）、id_token 全量校验（委托 {@link IdTokenVerifier}）。
 */
@Component
public class OidcClient {

    private final SsoProperties properties;
    private final RestClient restClient;
    private final IdTokenVerifier idTokenVerifier;

    public OidcClient(SsoProperties properties, RestClient.Builder restClientBuilder,
            IdTokenVerifier idTokenVerifier) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.getIssuer()).build();
        this.idTokenVerifier = idTokenVerifier;
    }

    /**
     * 构造 {@code /authorize} 跳转 URL（state/nonce 防 CSRF/重放）。
     */
    public String authorizeUrl(String state, String nonce) {
        return properties.getIssuer() + "/authorize"
                + "?client_id=" + enc(properties.getClientId())
                + "&redirect_uri=" + enc(properties.getRedirectUri())
                + "&response_type=code"
                + "&scope=" + enc(properties.getScope())
                + "&state=" + enc(state)
                + "&nonce=" + enc(nonce);
    }

    /**
     * 构造 identity RP-Initiated Logout 跳转 URL。
     *
     * <p>client_id 供 identity 解析 {@code post_logout_redirect_uri} 独立白名单；
     * state 原样回带；{@code idTokenHint} 取自当前会话持有的 id_token，SSO cookie
     * 丢失时 identity 凭 hint 的 sub 兜底定位会话（demo #40/#45），可为 null。</p>
     */
    public String logoutUrl(String postLogoutRedirectUri, String state, String idTokenHint) {
        String url = properties.getIssuer() + "/logout"
                + "?client_id=" + enc(properties.getClientId())
                + "&post_logout_redirect_uri=" + enc(postLogoutRedirectUri)
                + "&state=" + enc(state);
        if (idTokenHint != null && !idTokenHint.isBlank()) {
            url += "&id_token_hint=" + enc(idTokenHint);
        }
        return url;
    }

    /**
     * 用 code + client_secret 服务端换 token（grant_type=authorization_code，form body）。
     */
    public TokenResponse exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.getRedirectUri());
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        try {
            return restClient.post().uri("/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientResponseException e) {
            // /token 4xx/5xx：带出状态码 + 协议错误体（{error, error_description}）供日志排查
            throw new TokenExchangeException(
                    "identity /token 返回 " + e.getStatusCode().value(),
                    e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            // 连接/超时/2xx 解析失败等：无状态码/响应体，带原始原因
            throw new TokenExchangeException("identity /token 调用失败：" + e.getMessage(),
                    null, null, e);
        }
    }

    /**
     * id_token 全量校验（验签 + iss/aud/exp/nonce），委托 {@link IdTokenVerifier}。
     */
    public IdTokenClaims verifyIdToken(String idToken, String expectedNonce) {
        return idTokenVerifier.verify(idToken, expectedNonce);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
