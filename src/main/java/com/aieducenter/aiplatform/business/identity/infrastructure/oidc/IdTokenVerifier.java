package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.business.identity.domain.model.IdTokenClaims;

/**
 * id_token 校验器（A2 §2 表 3，第一天就做）：JWKS RS256 验签 +
 * {@code iss}/{@code aud}(=clientId)/{@code exp}/{@code nonce} 四项校验。
 *
 * <p>拒绝原因只进日志（{@code IDN_002}），不向前端暴露——callback 统一兜底
 * {@code /?error=exchange_failed}。</p>
 */
@Component
public class IdTokenVerifier {

    /** exp 校验的时钟偏差容忍（identity 与本服务各自身份的钟差余量） */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final SsoProperties properties;
    private final JwksKeySource keySource;
    private final Clock clock;

    @Autowired
    public IdTokenVerifier(SsoProperties properties, JwksKeySource keySource) {
        this(properties, keySource, Clock.systemUTC());
    }

    IdTokenVerifier(SsoProperties properties, JwksKeySource keySource, Clock clock) {
        this.properties = properties;
        this.keySource = keySource;
        this.clock = clock;
    }

    /**
     * 全量校验并解出用户声明；任一环节不过即抛 {@link IdTokenRejectedException}。
     *
     * @param expectedNonce 登录发起时签发的 nonce（id_token 内嵌值须精确一致，防重放）
     */
    public IdTokenClaims verify(String idToken, String expectedNonce) {
        SignedJWT jwt = parse(idToken);
        verifySignature(jwt);
        JWTClaimsSet claims = claimsOf(jwt);
        requireIssuer(claims);
        requireAudience(claims);
        requireNotExpired(claims);
        requireNonce(claims, expectedNonce);
        return new IdTokenClaims(
                claims.getSubject(),
                stringClaim(claims, "nickname"),
                stringClaim(claims, "name"),
                stringClaim(claims, "preferred_username"));
    }

    private static SignedJWT parse(String idToken) {
        try {
            return SignedJWT.parse(idToken);
        } catch (ParseException e) {
            throw new IdTokenRejectedException("id_token 解析失败", e);
        }
    }

    private void verifySignature(SignedJWT jwt) {
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            // 算法固定 RS256（discovery 自述）：拒掉 alg 混淆（none/HS256 等）
            throw new IdTokenRejectedException("签名算法非 RS256：" + jwt.getHeader().getAlgorithm());
        }
        String kid = jwt.getHeader().getKeyID();
        JWK key = keySource.keyFor(kid);
        if (!(key instanceof RSAKey rsaKey)) {
            throw new IdTokenRejectedException("JWKS 无此公钥（kid=" + kid + "）");
        }
        try {
            RSAPublicKey publicKey = rsaKey.toRSAPublicKey();
            JWSVerifier verifier = new RSASSAVerifier(publicKey);
            if (!jwt.verify(verifier)) {
                throw new IdTokenRejectedException("RS256 验签失败");
            }
        } catch (JOSEException e) {
            throw new IdTokenRejectedException("验签执行失败", e);
        }
    }

    private static JWTClaimsSet claimsOf(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new IdTokenRejectedException("id_token claims 解析失败", e);
        }
    }

    private void requireIssuer(JWTClaimsSet claims) {
        if (!properties.getIssuer().equals(claims.getIssuer())) {
            throw new IdTokenRejectedException("iss 不符：" + claims.getIssuer());
        }
    }

    private void requireAudience(JWTClaimsSet claims) {
        List<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(properties.getClientId())) {
            throw new IdTokenRejectedException("aud 不含本 client_id");
        }
    }

    private void requireNotExpired(JWTClaimsSet claims) {
        // 走原始 JSON 图取 exp（Number，epoch 秒）——JWTClaimsSet.getExpirationTime 返回
        // java.util.Date（项目禁用），payload 侧不经过类型转换
        Object expiration = payloadOf(claims).get("exp");
        if (!(expiration instanceof Number expirationSeconds)) {
            throw new IdTokenRejectedException("缺少 exp");
        }
        Instant expiredAt = Instant.ofEpochSecond(expirationSeconds.longValue());
        if (expiredAt.isBefore(clock.instant().minus(CLOCK_SKEW))) {
            throw new IdTokenRejectedException("id_token 已过期");
        }
    }

    private static java.util.Map<String, Object> payloadOf(JWTClaimsSet claims) {
        return claims.toPayload().toJSONObject();
    }

    private void requireNonce(JWTClaimsSet claims, String expectedNonce) {
        if (expectedNonce == null || expectedNonce.isBlank()) {
            throw new IdTokenRejectedException("缺少期望 nonce（事务 cookie 不完整）");
        }
        if (!expectedNonce.equals(stringClaim(claims, "nonce"))) {
            throw new IdTokenRejectedException("nonce 不匹配");
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        // getClaim 不抛 ParseException（getStringClaim 会）；非字符串声明按缺省处理
        Object value = claims.getClaim(name);
        return value instanceof String text ? text : null;
    }
}
