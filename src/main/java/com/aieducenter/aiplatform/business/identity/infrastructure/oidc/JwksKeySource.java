package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JWKS 公钥缓存（A2 §2 表 3：拉 {@code /jwks} 缓存 + RS256 验签）。
 *
 * <p>常规按 TTL 刷新；验签遇未知 {@code kid} 强制重拉一次——identity 轮换签名密钥
 * 后旧缓存自然收敛。重拉限频（{@link #MIN_REFETCH_INTERVAL}）：缓存被毒化或
 * identity 抖动时不会每个请求都打满 /jwks。</p>
 */
@Component
public class JwksKeySource {

    /** 常规刷新周期 */
    static final Duration TTL = Duration.ofHours(24);

    /** kid 未命中触发的强制重拉的最小间隔 */
    static final Duration MIN_REFETCH_INTERVAL = Duration.ofSeconds(5);

    private final JwksFetcher fetcher;
    private final Clock clock;

    private volatile JWKSet cached;
    private volatile Instant lastFetch = Instant.EPOCH;

    @Autowired
    public JwksKeySource(JwksFetcher fetcher) {
        this(fetcher, Clock.systemUTC());
    }

    JwksKeySource(JwksFetcher fetcher, Clock clock) {
        this.fetcher = fetcher;
        this.clock = clock;
    }

    /**
     * 取当前有效公钥集（过期则拉新）。
     */
    public JWKSet current() {
        if (cached == null || clock.instant().isAfter(lastFetch.plus(TTL))) {
            refresh();
        }
        return cached;
    }

    /**
     * 按 kid 取验签公钥；kid 未命中且距上次拉取超过限频间隔时强制重拉再找一次。
     * 仍无则返回 null（调用方按「未知 kid」拒绝验签）。
     */
    public JWK keyFor(String kid) {
        JWKSet set = current();
        JWK key = set == null ? null : set.getKeyByKeyId(kid);
        if (key != null || kid == null) {
            return key;
        }
        if (clock.instant().isAfter(lastFetch.plus(MIN_REFETCH_INTERVAL))) {
            refresh();
            key = cached == null ? null : cached.getKeyByKeyId(kid);
        }
        return key;
    }

    private synchronized void refresh() {
        cached = fetcher.fetch();
        lastFetch = clock.instant();
    }
}
