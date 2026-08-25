package com.aieducenter.aiplatform.base.chatagent.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aieducenter.aiplatform.base.chatagent.domain.error.ChatAgentMessage;
import com.cartisan.core.exception.DomainException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatAgentCommand} 入参校验（#44：runId/prompt/sessionId 必填，其余可空各取默认）。
 */
class ChatAgentCommandTest {

    private ChatAgentCommand command(String runId, String prompt, String sessionId) {
        return new ChatAgentCommand(runId, prompt, null, null, sessionId, null, null);
    }

    @Test
    void given_blank_run_id_when_construct_then_rejected() {
        assertThatThrownBy(() -> command(" ", "你好", "s-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ChatAgentMessage.COMMAND_FIELDS_INCOMPLETE.message());
    }

    @Test
    void given_blank_prompt_when_construct_then_rejected() {
        assertThatThrownBy(() -> command("run-1", "", "s-1"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_blank_session_id_when_construct_then_rejected() {
        assertThatThrownBy(() -> command("run-1", "你好", null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_full_command_when_construct_then_fields_kept() {
        UsageContext usage = new UsageContext("prj-1", Map.of("role", "BA"));

        ChatAgentCommand command = new ChatAgentCommand(
                "run-1", "你好", "你是 BA", "deepseek:deepseek-v4-flash",
                "s-1", "alice", usage);

        assertThat(command.runId()).isEqualTo("run-1");
        assertThat(command.prompt()).isEqualTo("你好");
        assertThat(command.systemPrompt()).isEqualTo("你是 BA");
        assertThat(command.modelString()).isEqualTo("deepseek:deepseek-v4-flash");
        assertThat(command.sessionId()).isEqualTo("s-1");
        assertThat(command.userId()).isEqualTo("alice");
        assertThat(command.usageContext()).isEqualTo(usage);
    }

    @Test
    void given_mutable_dims_when_construct_usage_context_then_defensively_copied() {
        Map<String, String> dims = new HashMap<>(Map.of("role", "BA"));
        UsageContext usage = new UsageContext("prj-1", dims);

        dims.put("role", "tampered");

        assertThat(usage.dims()).containsEntry("role", "BA");
    }
}
