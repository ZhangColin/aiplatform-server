package com.aieducenter.aiplatform.base.workspace.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行结果：exitCode 语义（0 = 成功；非 0 是命令失败不是环境故障）。
 */
class ExecResultTest {

    @Test
    void given_zero_exit_code_when_ok_then_true() {
        assertThat(new ExecResult("hi", "", 0).ok()).isTrue();
    }

    @Test
    void given_non_zero_exit_code_when_ok_then_false() {
        assertThat(new ExecResult("", "not found", 127).ok()).isFalse();
    }
}
