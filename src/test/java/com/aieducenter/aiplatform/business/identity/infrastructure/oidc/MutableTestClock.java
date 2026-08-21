package com.aieducenter.aiplatform.business.identity.infrastructure.oidc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 可拨动时钟（exp 容忍、JWKS TTL / 重拉限频等时间语义测试共用）。
 */
class MutableTestClock extends Clock {

    private Instant now;

    MutableTestClock(Instant now) {
        this.now = now;
    }

    void advanceBySeconds(long seconds) {
        now = now.plusSeconds(seconds);
    }

    Instant now() {
        return now;
    }

    @Override
    public ZoneOffset getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
