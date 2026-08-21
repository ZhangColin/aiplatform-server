package com.aieducenter.aiplatform.base.workspace.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 环境种类枚举：code/name 契约（库列与 API 契约的稳定编码）。
 */
class EnvKindTest {

    @Test
    void given_env_kinds_when_inspect_then_code_and_name_mapped() {
        assertThat(EnvKind.DEV.getCode()).isEqualTo(1);
        assertThat(EnvKind.DEV.getName()).isEqualTo("开发");
        assertThat(EnvKind.TEST.getCode()).isEqualTo(2);
        assertThat(EnvKind.TEST.getName()).isEqualTo("测试");
        assertThat(EnvKind.PROD.getCode()).isEqualTo(3);
        assertThat(EnvKind.PROD.getName()).isEqualTo("生产");
    }
}
