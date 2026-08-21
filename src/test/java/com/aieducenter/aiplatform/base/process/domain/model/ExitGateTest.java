package com.aieducenter.aiplatform.base.process.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 出口门不变量单测：actor 非空白、minTasks ≥ 0（验收门 = 0 可表达，A3 §2.4）。
 */
class ExitGateTest {

    @Test
    void given_zero_min_tasks_when_construct_then_allowed() {
        // 验收门 = 0：计数门禁恒放行（验收段无 agent 任务）
        ExitGate gate = new ExitGate("用户", 0);

        assertThat(gate.minTasks()).isZero();
    }

    @Test
    void given_blank_actor_when_construct_then_rejected() {
        assertThatThrownBy(() -> new ExitGate("  ", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("拍板方");
    }

    @Test
    void given_negative_min_tasks_when_construct_then_rejected() {
        assertThatThrownBy(() -> new ExitGate("用户", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最低任务数");
    }
}
