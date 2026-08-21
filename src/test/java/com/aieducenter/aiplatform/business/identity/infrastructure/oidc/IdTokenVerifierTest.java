package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.business.identity.domain.model.IdTokenClaims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * id_token 校验器（A2 §2 表 3 的安全核心）：测试内生成 RSA 密钥对自签 JWT，
 * 逐项验证 验签 / alg 固定 / iss / aud / exp（含钟差容忍）/ nonce 与 kid 轮换重拉。
 */
class IdTokenVerifierTest {

    private static final String ISSUER = "http://identity.localhost:10001";
    private static final String CLIENT_ID = "aiplatform-client";
    private static final String KID = "identity-rs256-v1";

    private RSAKey identityKey;
    private RSAPrivateKey signingKey;
    private MutableTestClock clock;
    private IdTokenVerifier verifier;
    private JwksKeySource keySource;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException, JOSEException {
        identityKey = rsaKey(KID);
        signingKey = identityKey.toRSAPrivateKey();
        clock = new MutableTestClock(Instant.parse("2026-08-21T10:00:00Z"));
        keySource = new JwksKeySource(() -> new JWKSet(identityKey), clock);
        verifier = new IdTokenVerifier(props(), keySource, clock);
    }

    // -------- happy path --------

    @Test
    void given_valid_id_token_when_verify_then_claims_mapped() {
        String token = token(claims());

        IdTokenClaims claims = verifier.verify(token, "nonce-1");

        assertThat(claims.subject()).isEqualTo("sub-123");
        assertThat(claims.nickname()).isEqualTo("张三");
        assertThat(claims.name()).isEqualTo("张三丰");
        assertThat(claims.preferredUsername()).isEqualTo("zhangsan");
    }

    // -------- 验签与算法 --------

    @Test
    void given_token_signed_by_unknown_key_when_verify_then_rejected()
            throws NoSuchAlgorithmException, JOSEException {
        RSAKey attackerKey = rsaKey("attacker-v1");
        String token = token(claims(), attackerKey.toRSAPrivateKey(), "attacker-v1");

        assertThatThrownBy(() -> verifier.verify(token, "nonce-1"))
                .isInstanceOf(IdTokenRejectedException.class)
                .hasMessageContaining("JWKS 无此公钥");
    }

    @Test
    void given_alg_not_rs256_when_verify_then_rejected() {
        // 算法混淆防线：同 payload 换 HS256 头直接拒（alg 校验在验签之前，签名段可为任意占位）
        String hs256Token = com.nimbusds.jose.util.Base64URL.encode(
                        new JWSHeader(JWSAlgorithm.HS256).toString())
                + "." + com.nimbusds.jose.util.Base64URL.encode(claims().toString())
                + "." + com.nimbusds.jose.util.Base64URL.encode("dummy-signature");

        assertThatThrownBy(() -> verifier.verify(hs256Token, "nonce-1"))
                .isInstanceOf(IdTokenRejectedException.class)
                .hasMessageContaining("签名算法非 RS256");
    }

    @Test
    void given_garbage_token_when_verify_then_rejected() {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt", "nonce-1"))
                .isInstanceOf(IdTokenRejectedException.class);
    }

    // -------- iss / aud / exp / nonce --------

    @Test
    void given_wrong_issuer_when_verify_then_rejected() {
        JWTClaimsSet claims = claimsBuilder().issuer("https://evil.example.com").build();
        assertThatThrownBy(() -> verifier.verify(token(claims), "nonce-1"))
                .hasMessageContaining("iss 不符");
    }

    @Test
    void given_audience_without_client_id_when_verify_then_rejected() {
        JWTClaimsSet claims = claimsBuilder().audience("other-client").build();
        assertThatThrownBy(() -> verifier.verify(token(claims), "nonce-1"))
                .hasMessageContaining("aud 不含本 client_id");
    }

    @Test
    void given_expired_beyond_skew_when_verify_then_rejected() {
        // exp 已过 61s（钟差容忍 60s 之外）
        JWTClaimsSet claims = claimsBuilder()
                .expirationTime(Date.from(clock.now().minusSeconds(61)))
                .build();
        assertThatThrownBy(() -> verifier.verify(token(claims), "nonce-1"))
                .hasMessageContaining("已过期");
    }

    @Test
    void given_expired_within_skew_when_verify_then_accepted() {
        // exp 刚过 30s，钟差容忍内放行（identity / 本服务各自的钟差余量）
        JWTClaimsSet claims = claimsBuilder()
                .expirationTime(Date.from(clock.now().minusSeconds(30)))
                .build();

        assertThat(verifier.verify(token(claims), "nonce-1").subject()).isEqualTo("sub-123");
    }

    @Test
    void given_missing_exp_when_verify_then_rejected() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(CLIENT_ID).subject("sub-123").claim("nonce", "nonce-1")
                .build();
        assertThatThrownBy(() -> verifier.verify(token(claims), "nonce-1"))
                .hasMessageContaining("缺少 exp");
    }

    @Test
    void given_nonce_mismatch_when_verify_then_rejected() {
        assertThatThrownBy(() -> verifier.verify(token(claims()), "different-nonce"))
                .hasMessageContaining("nonce 不匹配");
    }

    @Test
    void given_missing_expected_nonce_when_verify_then_rejected() {
        // 事务 cookie 缺 nonce 段（解析失败路径的下游防线）
        assertThatThrownBy(() -> verifier.verify(token(claims()), null))
                .hasMessageContaining("缺少期望 nonce");
    }

    // -------- kid 轮换：未知 kid 触发 JWKS 重拉 --------

    @Test
    void given_unknown_kid_after_rotation_when_verify_then_refetch_and_accept()
            throws NoSuchAlgorithmException, JOSEException {
        RSAKey newKey = rsaKey("identity-rs256-v2");
        CountingFetcher fetcher = new CountingFetcher(new JWKSet(identityKey), new JWKSet(newKey));
        JwksKeySource rotatingSource = new JwksKeySource(fetcher, clock);
        IdTokenVerifier rotatingVerifier = new IdTokenVerifier(props(), rotatingSource, clock);

        // 先用旧 key 预热缓存
        rotatingVerifier.verify(token(claims()), "nonce-1");
        assertThat(fetcher.calls()).isEqualTo(1);

        // 走过重拉限频间隔（否则 kid 未命中也不打 /jwks）
        clock.advanceBySeconds(JwksKeySource.MIN_REFETCH_INTERVAL.plusSeconds(1).toSeconds());

        // 轮换后：新 kid 不在旧缓存 → 强制重拉 → 验签通过
        rotatingVerifier.verify(token(claims(), newKey.toRSAPrivateKey(), "identity-rs256-v2"),
                "nonce-1");
        assertThat(fetcher.calls()).isEqualTo(2);
    }

    // -------- 测试工具 --------

    private static SsoProperties props() {
        SsoProperties properties = new SsoProperties();
        properties.setIssuer(ISSUER);
        properties.setClientId(CLIENT_ID);
        properties.setClientSecret("secret");
        properties.setRedirectUri("http://localhost:3333/auth/callback");
        properties.setAppBaseUrl("http://localhost:3333");
        return properties;
    }

    private static RSAKey rsaKey(String kid) throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyUse(KeyUse.SIGNATURE)
                .keyID(kid)
                .build();
    }

    private JWTClaimsSet.Builder claimsBuilder() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(CLIENT_ID)
                .subject("sub-123")
                .claim("nickname", "张三")
                .claim("name", "张三丰")
                .claim("preferred_username", "zhangsan")
                .claim("nonce", "nonce-1")
                .expirationTime(Date.from(clock.now().plus(5, ChronoUnit.MINUTES)));
    }

    private JWTClaimsSet claims() {
        return claimsBuilder().build();
    }

    private String token(JWTClaimsSet claims) {
        return token(claims, signingKey, KID);
    }

    private String token(JWTClaimsSet claims, RSAPrivateKey key, String kid) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 首次返回旧公钥集、之后返回新公钥集（模拟 identity 轮换后的 /jwks） */
    private static final class CountingFetcher implements JwksFetcher {
        private final JWKSet first;
        private final JWKSet second;
        private int calls;

        private CountingFetcher(JWKSet first, JWKSet second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public JWKSet fetch() {
            calls++;
            return calls == 1 ? first : second;
        }

        int calls() {
            return calls;
        }
    }
}
