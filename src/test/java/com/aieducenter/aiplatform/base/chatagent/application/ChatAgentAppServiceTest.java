package com.aieducenter.aiplatform.base.chatagent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.port.ChatAgentClient;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ChatAgentAppService} 流桥（#45）：适配器帧 → 既有 agent 流通道——关联字段
 * 逐帧注入（projectId 对齐编码引擎 run 口径）、帧本体字段优先、发射异常不拖垮对话。
 */
@ExtendWith(MockitoExtension.class)
class ChatAgentAppServiceTest {

    @Mock
    private ChatAgentClient chatAgentClient;

    @Mock
    private AgentStreamAppService streamAppService;

    @Captor
    private ArgumentCaptor<Consumer<com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent>> sinkCaptor;

    @Captor
    private ArgumentCaptor<Map<String, Object>> payloadCaptor;

    @Test
    void given_correlation_when_converse_then_every_frame_published_with_correlation_injected() {
        ChatAgentCommand command = new ChatAgentCommand("run-1", "写 PRD", null, null,
                "s-1", "alice", null, "42", Map.of("projectId", "42"));
        when(chatAgentClient.converse(eq(command), any()))
                .thenReturn(new ChatAgentReply("run-1", "好的"));
        ChatAgentAppService appService = new ChatAgentAppService(chatAgentClient, streamAppService);

        ChatAgentReply reply = appService.converse(command);

        assertThat(reply.text()).isEqualTo("好的");
        // 适配器侧 sink 被透传给 client（帧零丢失）
        verify(chatAgentClient).converse(eq(command), sinkCaptor.capture());
        sinkCaptor.getValue().accept(new AgentEvent(AgentEventTypes.TASK_START,
                Map.of("runId", "run-1", "prompt", "写 PRD")));
        sinkCaptor.getValue().accept(new AgentEvent("text",
                Map.of("runId", "run-1", "data", Map.of("delta", "好"))));

        verify(streamAppService, times(2)).publish(any(), payloadCaptor.capture());
        List<Map<String, Object>> payloads = payloadCaptor.getAllValues();
        // 关联字段注入 + 帧本体字段（runId/prompt）优先
        assertThat(payloads.get(0)).containsEntry("projectId", "42")
                .containsEntry("runId", "run-1")
                .containsEntry("prompt", "写 PRD");
        assertThat(payloads.get(1)).containsEntry("projectId", "42")
                .containsEntry("runId", "run-1")
                .containsEntry("data", Map.of("delta", "好"));
    }

    @Test
    void given_no_correlation_when_converse_then_payload_untouched() {
        ChatAgentCommand command = new ChatAgentCommand("run-2", "你好", null, null,
                "s-2", null, null, null, null);
        when(chatAgentClient.converse(eq(command), any()))
                .thenReturn(new ChatAgentReply("run-2", "你好呀"));
        ChatAgentAppService appService = new ChatAgentAppService(chatAgentClient, streamAppService);

        appService.converse(command);

        verify(chatAgentClient).converse(eq(command), sinkCaptor.capture());
        sinkCaptor.getValue().accept(new AgentEvent(AgentEventTypes.TASK_FINISH,
                Map.of("runId", "run-2")));

        verify(streamAppService).publish(eq(AgentEventTypes.TASK_FINISH), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsOnly(Map.entry("runId", "run-2"));
    }

    @Test
    void given_publish_failure_when_frame_emitted_then_converse_not_affected() {
        ChatAgentCommand command = new ChatAgentCommand("run-3", "继续", null, null,
                "s-3", null, null, null, Map.of("projectId", "7"));
        when(chatAgentClient.converse(eq(command), any()))
                .thenReturn(new ChatAgentReply("run-3", "done"));
        doThrow(new IllegalStateException("channel down")).when(streamAppService)
                .publish(any(), any());
        ChatAgentAppService appService = new ChatAgentAppService(chatAgentClient, streamAppService);

        assertThatCode(() -> {
            ChatAgentReply reply = appService.converse(command);
            assertThat(reply.runId()).isEqualTo("run-3");
        }).doesNotThrowAnyException();

        verify(chatAgentClient).converse(eq(command), sinkCaptor.capture());
        // 发射失败不回流进适配器（sink 吞异常只记日志）
        assertThatCode(() -> sinkCaptor.getValue().accept(
                new AgentEvent(AgentEventTypes.TASK_START, Map.of("runId", "run-3"))))
                .doesNotThrowAnyException();
    }
}
