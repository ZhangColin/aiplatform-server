package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OIDC 客户端：authorize/logout URL 构造 + code 换 token（form body 姿态、
 * snake_case 映射、非 2xx 细节带出——identity demo issue #35 教训）。
 */
class OidcClientTest {

    private static final String ISSUER = "http://identity.localhost:10001";

    private SsoProperties properties;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;
    private OidcClient client;

    @BeforeEach
    void setUp() {
        properties = new SsoProperties();
        properties.setIssuer(ISSUER);
        properties.setClientId("client id+&");   // 需编码的字符
        properties.setClientSecret("secret&=");
        properties.setRedirectUri("http://localhost:3333/auth/callback");
        properties.setScope("openid profile email");
        properties.setAppBaseUrl("http://localhost:3333");
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new OidcClient(properties, restClientBuilder,
                new IdTokenVerifier(properties,
                        new JwksKeySource(() -> { throw new IllegalStateException(); })));
    }

    @Test
    void given_login_request_when_authorize_url_then_all_params_url_encoded() throws Exception {
        String url = client.authorizeUrl("st/ate", "non ce");

        java.util.Map<String, String> query = queryOf(url);
        assertThat(url).startsWith(ISSUER + "/authorize?");
        assertThat(query).containsEntry("client_id", "client id+&");
        assertThat(query).containsEntry("redirect_uri", "http://localhost:3333/auth/callback");
        assertThat(query).containsEntry("response_type", "code");
        assertThat(query).containsEntry("scope", "openid profile email");
        assertThat(query).containsEntry("state", "st/ate");
        assertThat(query).containsEntry("nonce", "non ce");
    }

    @Test
    void given_logout_without_hint_when_logout_url_then_no_id_token_hint_param() throws Exception {
        String url = client.logoutUrl("http://localhost:3333/", "logout-state", null);

        assertThat(url).startsWith(ISSUER + "/logout?");
        java.util.Map<String, String> query = queryOf(url);
        assertThat(query).containsEntry("post_logout_redirect_uri", "http://localhost:3333/");
        assertThat(query).containsEntry("state", "logout-state");
        assertThat(url).doesNotContain("id_token_hint");
    }

    @Test
    void given_logout_with_hint_when_logout_url_then_hint_appended() throws Exception {
        String url = client.logoutUrl("http://localhost:3333/", "s", "jwt-value");

        assertThat(queryOf(url)).containsEntry("id_token_hint", "jwt-value");
    }

    @Test
    void given_code_when_exchange_then_form_posted_and_snake_case_mapped() {
        server.expect(requestTo(ISSUER + "/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(formWithAllGrantParams()))
                .andRespond(withSuccess("""
                        {"access_token":"at","token_type":"Bearer","expires_in":3600,
                         "refresh_token":"rt","id_token":"it"}
                        """, MediaType.APPLICATION_JSON));

        TokenResponse tokens = client.exchangeCode("code-1");

        assertThat(tokens.accessToken()).isEqualTo("at");
        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresIn()).isEqualTo(3600);
        assertThat(tokens.refreshToken()).isEqualTo("rt");
        assertThat(tokens.idToken()).isEqualTo("it");
        server.verify();
    }

    @Test
    void given_identity_rejects_code_when_exchange_then_details_carried_for_logging() {
        server.expect(requestTo(ISSUER + "/token"))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"code 已用\"}"));

        assertThatThrownBy(() -> client.exchangeCode("used-code"))
                .isInstanceOfSatisfying(TokenExchangeException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(400);
                    assertThat(e.responseBody()).contains("invalid_grant");
                });
    }

    @Test
    void given_identity_unreachable_when_exchange_then_wrapped_without_status() {
        // 连接级失败替身：无状态码/响应体（重点是 RestClientException 分支被兜住）
        server.expect(requestTo(ISSUER + "/token"))
                .andRespond(request -> {
                    throw new org.springframework.web.client.ResourceAccessException("连接拒绝");
                });

        assertThatThrownBy(() -> client.exchangeCode("code-1"))
                .isInstanceOfSatisfying(TokenExchangeException.class,
                        e -> assertThat(e.httpStatus()).isNull());
    }

    // -------- 测试工具（期望值与 setUp 的配置同源，勿单边改） --------

    private static MultiValueMap<String, String> formWithAllGrantParams() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", "code-1");
        form.add("redirect_uri", "http://localhost:3333/auth/callback");
        form.add("client_id", "client id+&");
        form.add("client_secret", "secret&=");
        return form;
    }

    private static java.util.Map<String, String> queryOf(String url) throws Exception {
        java.util.Map<String, String> query = new java.util.LinkedHashMap<>();
        String raw = URI.create(url).getRawQuery();
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            query.put(pair.substring(0, idx),
                    java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return query;
    }
}
