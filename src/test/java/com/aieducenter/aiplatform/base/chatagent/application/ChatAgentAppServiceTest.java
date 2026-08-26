package com.aieducenter.aiplatform.base.chatagent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.port.ChatAgentClient;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentWorkspaceClient;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
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

    @Mock
    private AgentWaitAppService waitAppService;

    @Mock
    private ChatAgentWorkspaceClient workspaceClient;

    @Captor
    private ArgumentCaptor<Consumer<com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent>> sinkCaptor;

    @Captor
    private ArgumentCaptor<Map<String, Object>> payloadCaptor;

    private ChatAgentAppService appService() {
        return new ChatAgentAppService(chatAgentClient, streamAppService, waitAppService,
                workspaceClient);
    }

    @Test
    void given_correlation_when_converse_then_every_frame_published_with_correlation_injected() {
        ChatAgentCommand command = new ChatAgentCommand("run-1", "写 PRD", null, null,
                "s-1", "alice", null, "42", Map.of("projectId", "42"));
        when(chatAgentClient.converse(eq(command), any()))
                .thenReturn(new ChatAgentReply("run-1", "好的"));
        ChatAgentAppService appService = appService();

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
        ChatAgentAppService appService = appService();

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
        ChatAgentAppService appService = appService();

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

    // ---------- #48：等待点双向桥（流桥拦截） ----------

    @Test
    void given_bridged_workspace_when_wait_raised_then_registered_and_published_with_wait_id() {
        when(workspaceClient.handleOf("42")).thenReturn(WorkspaceHandle.dev(
                WorkspaceId.of("42"), "ws-42-dev", "net-42", 0, 0));
        when(waitAppService.raiseFromEvent(eq(42L), any())).thenReturn(new WaitPointResponse(
                "wait-1", "42", "s-1", "run-1", "reply-9",
                null, null, null, null, null, null, null, null, null, null));
        Consumer<AgentEvent> sink = appService().sink("42", Map.of("projectId", "42"));

        sink.accept(new AgentEvent(AgentEventTypes.WAIT_RAISED, Map.of(
                AgentEventTypes.WAIT_RUN_FIELD, "run-1",
                AgentEventTypes.WAIT_SESSION_FIELD, "s-1",
                AgentEventTypes.WAIT_KIND_FIELD, "PERMISSION",
                AgentEventTypes.WAIT_SUMMARY_FIELD, "write_file",
                AgentEventTypes.WAIT_ENGINE_REF_FIELD, "reply-9",
                AgentEventTypes.WAIT_DATA_FIELD, Map.of())));

        // 落库即闭 + 关联方补发（带 waitId；挂起 REST 可查、SSE 可见）
        verify(waitAppService).raiseFromEvent(eq(42L), any());
        verify(streamAppService).publish(eq(AgentEventTypes.WAIT_RAISED), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry(AgentEventTypes.WAIT_ID_FIELD, "wait-1")
                .containsEntry("projectId", "42");
    }

    @Test
    void given_bridged_workspace_when_run_terminal_then_pending_waits_expired() {
        Consumer<AgentEvent> sink = appService().sink("42", null);

        sink.accept(new AgentEvent(AgentEventTypes.TASK_FINISH, Map.of("runId", "run-9")));

        // run 终态联动：其 PENDING 等待点 → EXPIRED（挂起轮不发 task-finish，正常
        // 收口/超时 error 才走这里——同编码引擎口径）
        verify(waitAppService).expireRun("run-9");
        verify(streamAppService).publish(eq(AgentEventTypes.TASK_FINISH), any());
    }

    @Test
    void given_no_workspace_when_wait_raised_then_passthrough_without_registration() {
        // 本地兜底（无 workspaceId）不拦截：wait-raised 透传、不落库不联动（#45 口径）
        Consumer<AgentEvent> sink = appService().sink(null, null);

        sink.accept(new AgentEvent(AgentEventTypes.WAIT_RAISED, Map.of(
                AgentEventTypes.WAIT_RUN_FIELD, "run-1",
                AgentEventTypes.WAIT_SESSION_FIELD, "s-1",
                AgentEventTypes.WAIT_KIND_FIELD, "QUESTION",
                AgentEventTypes.WAIT_SUMMARY_FIELD, "ask_user",
                AgentEventTypes.WAIT_ENGINE_REF_FIELD, "reply-9",
                AgentEventTypes.WAIT_DATA_FIELD, Map.of())));

        verify(waitAppService, org.mockito.Mockito.never()).raiseFromEvent(anyLong(), any());
        verify(streamAppService).publish(eq(AgentEventTypes.WAIT_RAISED), any());
    }

    @Test
    void given_registration_failure_when_wait_raised_then_stream_not_killed() {
        when(workspaceClient.handleOf("42")).thenReturn(WorkspaceHandle.dev(
                WorkspaceId.of("42"), "ws-42-dev", "net-42", 0, 0));
        when(waitAppService.raiseFromEvent(anyLong(), any()))
                .thenThrow(new IllegalStateException("db down"));
        Consumer<AgentEvent> sink = appService().sink("42", null);

        // 落库失败不拖垮流桥（护栏与编码引擎同款；等待点丢失可经重上报收敛）
        org.assertj.core.api.Assertions.assertThatCode(() -> sink.accept(
                new AgentEvent(AgentEventTypes.WAIT_RAISED, Map.of(
                        AgentEventTypes.WAIT_RUN_FIELD, "run-1",
                        AgentEventTypes.WAIT_SESSION_FIELD, "s-1",
                        AgentEventTypes.WAIT_KIND_FIELD, "QUESTION",
                        AgentEventTypes.WAIT_SUMMARY_FIELD, "",
                        AgentEventTypes.WAIT_ENGINE_REF_FIELD, "reply-9",
                        AgentEventTypes.WAIT_DATA_FIELD, Map.of()))))
                .doesNotThrowAnyException();
    }
}
