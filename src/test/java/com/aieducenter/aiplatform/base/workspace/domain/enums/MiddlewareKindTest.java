package com.aieducenter.aiplatform.base.workspace.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中间件种类枚举：code/name 契约（库列与 API 契约的稳定编码）。
 */
class MiddlewareKindTest {

    @Test
    void given_middleware_kinds_when_inspect_then_code_and_name_mapped() {
        assertThat(MiddlewareKind.POSTGRESQL.getCode()).isEqualTo(1);
        assertThat(MiddlewareKind.POSTGRESQL.getName()).isEqualTo("PostgreSQL");
        assertThat(MiddlewareKind.REDIS.getCode()).isEqualTo(2);
        assertThat(MiddlewareKind.REDIS.getName()).isEqualTo("Redis");
    }
}
