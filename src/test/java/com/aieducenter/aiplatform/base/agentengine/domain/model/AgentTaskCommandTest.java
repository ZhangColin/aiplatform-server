package com.aieducenter.aiplatform.base.agentengine.domain.model;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务命令不变量：runId 任务端点生成必填（全部流事件关联键）、prompt 必填；
 * usageContext 可空缝（调用方不归属）与 dims 防御拷贝。
 */
class AgentTaskCommandTest {

    @Test
    void given_full_command_when_construct_then_fields_kept() {
        AgentTaskCommand command = new AgentTaskCommand("run-1", "写个落地页",
                "你是 BA", "deepseek-v4-pro", "ses_abc",
                new UsageContext("proj-1", Map.of("role", "BA")));

        assertThat(command.runId()).isEqualTo("run-1");
        assertThat(command.sessionId()).isEqualTo("ses_abc");
        assertThat(command.usageContext().subject()).isEqualTo("proj-1");
    }

    @Test
    void given_blank_run_id_or_prompt_when_construct_then_rejected() {
        assertThatThrownBy(() -> new AgentTaskCommand(" ", "写个落地页", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
        assertThatThrownBy(() -> new AgentTaskCommand("run-1", " ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt");
    }

    @Test
    void given_usage_context_with_null_dims_when_construct_then_empty_map() {
        UsageContext context = new UsageContext("proj-1", null);

        assertThat(context.dims()).isEmpty();
    }

    @Test
    void given_blank_subject_when_usage_context_then_rejected() {
        assertThatThrownBy(() -> new UsageContext(" ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
    }
}
