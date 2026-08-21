package com.aieducenter.aiplatform.base.workspace.domain.entity;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 中间件资源实体：业务键身份（workspaceId + kind）+ 构造不变量。
 */
class MiddlewareResourceTest {

    @Test
    void given_same_workspace_and_kind_when_equals_then_true_even_names_differ() {
        MiddlewareResource pg1 = new MiddlewareResource(1L, MiddlewareKind.POSTGRESQL,
                "pg-a", 5432, "postgresql://a");
        MiddlewareResource pg2 = new MiddlewareResource(1L, MiddlewareKind.POSTGRESQL,
                "pg-b", 5433, "postgresql://b");

        assertThat(pg1).isEqualTo(pg2);
        assertThat(pg1).hasSameHashCodeAs(pg2);
    }

    @Test
    void given_different_kind_when_equals_then_false() {
        MiddlewareResource pg = new MiddlewareResource(1L, MiddlewareKind.POSTGRESQL,
                "pg-a", 5432, "postgresql://a");
        MiddlewareResource redis = new MiddlewareResource(1L, MiddlewareKind.REDIS,
                "rd-a", 6379, "redis://a");

        assertThat(pg).isNotEqualTo(redis);
    }

    @Test
    void given_incomplete_fields_when_construct_then_rejected() {
        // 各不变量分支（WSP_006）：空 url / 空 workspaceId / 空 kind / 空容器名
        assertThatThrownBy(() -> new MiddlewareResource(1L, MiddlewareKind.REDIS,
                "rd-a", 6379, " "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("中间件资源字段不完整");
        assertThatThrownBy(() -> new MiddlewareResource(null, MiddlewareKind.REDIS,
                "rd-a", 6379, "redis://a"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new MiddlewareResource(1L, null,
                "rd-a", 6379, "redis://a"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new MiddlewareResource(1L, MiddlewareKind.REDIS,
                " ", 6379, "redis://a"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_same_instance_when_equals_then_true() {
        MiddlewareResource redis = new MiddlewareResource(1L, MiddlewareKind.REDIS,
                "rd-a", 6379, "redis://a");

        assertThat(redis).isEqualTo(redis);
    }

    @Test
    void given_different_type_when_equals_then_false() {
        MiddlewareResource redis = new MiddlewareResource(1L, MiddlewareKind.REDIS,
                "rd-a", 6379, "redis://a");

        assertThat(redis).isNotEqualTo("rd-a");
        assertThat(redis).isNotEqualTo(null);
    }

    @Test
    void given_same_business_key_when_hash_code_then_consistent_with_equals() {
        MiddlewareResource pg1 = new MiddlewareResource(1L, MiddlewareKind.POSTGRESQL,
                "pg-a", 5432, "postgresql://a");
        MiddlewareResource pg2 = new MiddlewareResource(1L, MiddlewareKind.POSTGRESQL,
                "pg-a", 5432, "postgresql://a");
        MiddlewareResource other = new MiddlewareResource(2L, MiddlewareKind.POSTGRESQL,
                "pg-b", 5432, "postgresql://b");

        assertThat(pg1).hasSameHashCodeAs(pg2);
        assertThat(pg1.hashCode()).isNotEqualTo(other.hashCode());
    }
}
