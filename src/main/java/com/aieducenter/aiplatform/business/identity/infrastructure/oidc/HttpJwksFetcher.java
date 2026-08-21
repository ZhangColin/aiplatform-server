package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

import java.text.ParseException;

import com.nimbusds.jose.jwk.JWKSet;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.Builder;

/**
 * {@code GET {issuer}/jwks} 拉取（公开端点，无需凭据）。
 */
@Component
public class HttpJwksFetcher implements JwksFetcher {

    private final RestClient restClient;

    public HttpJwksFetcher(SsoProperties properties, RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl(properties.getIssuer()).build();
    }

    @Override
    public JWKSet fetch() {
        String body = restClient.get().uri("/jwks")
                .retrieve()
                .body(String.class);
        try {
            return JWKSet.parse(body);
        } catch (ParseException e) {
            throw new IllegalStateException("JWKS 解析失败", e);
        }
    }
}
