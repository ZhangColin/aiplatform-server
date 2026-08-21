package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWKS 缓存语义：常规 TTL 内不重复拉、过期重拉、kid 未命中限频强制重拉。
 */
class JwksKeySourceTest {

    private final MutableTestClock clock = new MutableTestClock(Instant.parse("2026-08-21T10:00:00Z"));
    private final CountingFetcher fetcher = new CountingFetcher();

    @Test
    void given_fresh_cache_when_current_then_no_refetch() {
        JwksKeySource source = new JwksKeySource(fetcher, clock);

        source.current();
        source.current();

        assertThat(fetcher.calls).isEqualTo(1);
    }

    @Test
    void given_cache_older_than_ttl_when_current_then_refetched() {
        JwksKeySource source = new JwksKeySource(fetcher, clock);

        source.current();
        clock.advanceBySeconds(JwksKeySource.TTL.plusSeconds(1).toSeconds());
        source.current();

        assertThat(fetcher.calls).isEqualTo(2);
    }

    @Test
    void given_unknown_kid_within_rate_limit_when_key_for_then_no_refetch() {
        JwksKeySource source = new JwksKeySource(fetcher, clock);

        source.current();
        // 未过限频间隔：kid 未命中也不打 /jwks（缓存毒化/上游抖动保护）
        assertThat(source.keyFor("rotated-kid")).isNull();

        assertThat(fetcher.calls).isEqualTo(1);
    }

    @Test
    void given_unknown_kid_after_rate_limit_when_key_for_then_refetched() {
        JwksKeySource source = new JwksKeySource(fetcher, clock);

        source.current();
        clock.advanceBySeconds(JwksKeySource.MIN_REFETCH_INTERVAL.plusSeconds(1).toSeconds());
        assertThat(source.keyFor("rotated-kid")).isNull();

        assertThat(fetcher.calls).isEqualTo(2);
    }

    // -------- 测试工具 --------

    private static final class CountingFetcher implements JwksFetcher {
        private int calls;

        @Override
        public JWKSet fetch() {
            calls++;
            try {
                RSAKey key = new RSAKeyGenerator(2048)
                        .keyUse(KeyUse.SIGNATURE)
                        .keyID("identity-rs256-v1")
                        .generate();
                return new JWKSet(key);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
