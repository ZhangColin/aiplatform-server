package com.aieducenter.aiplatform.business.identity.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;
import com.cartisan.core.exception.CodeMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账号聚合（A2 §3）：自动建档与显示名同步——同 sub 二次登录不重复建档、显示名变更更新。
 */
class AccountTest {

    @Test
    void given_first_login_when_register_then_fields_populated_with_tsid_id() {
        Account account = Account.register("sub-abc", "张三");

        assertThat(account.getId()).isPositive();
        assertThat(account.getExternalId()).isEqualTo("sub-abc");
        assertThat(account.getDisplayName()).isEqualTo("张三");
    }

    @Test
    void given_incomplete_fields_when_register_then_rejected() {
        assertThatThrownBy(() -> Account.register(null, "张三"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(code(e)).isEqualTo("IDN_001"));
        assertThatThrownBy(() -> Account.register("sub-abc", " "))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(code(e)).isEqualTo("IDN_001"));
    }

    @Test
    void given_oversized_fields_when_register_then_rejected() {
        assertThatThrownBy(() -> Account.register("s".repeat(101), "张三"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(code(e)).isEqualTo("IDN_001"));
        assertThatThrownBy(() -> Account.register("sub-abc", "名".repeat(201)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(code(e)).isEqualTo("IDN_001"));
    }

    private static String code(DomainException exception) {
        return exception.getCodeMessage().code();
    }

    @Test
    void given_changed_display_name_when_sync_then_updated_and_reported() {
        Account account = Account.register("sub-abc", "张三");

        boolean changed = account.syncDisplayName("李四");

        assertThat(changed).isTrue();
        assertThat(account.getDisplayName()).isEqualTo("李四");
    }

    @Test
    void given_unchanged_display_name_when_sync_then_no_op_and_not_reported() {
        Account account = Account.register("sub-abc", "张三");

        assertThat(account.syncDisplayName("张三")).isFalse();
        assertThat(account.getDisplayName()).isEqualTo("张三");
    }
}
