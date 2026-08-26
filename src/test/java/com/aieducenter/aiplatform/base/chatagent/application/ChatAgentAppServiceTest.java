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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.port.ChatAgentClient;
import com.aieducenter.aiplatform.base.chatagent.domain.error.ChatAgentMessage;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentWorkspaceClient;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope.ChatAgentResumeGate;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.cartisan.core.exception.DomainException;
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
        // 同步路径不走闸；直通闸占位满足构造（#40 异步入口同用：提交即执行）
        return new ChatAgentAppService(chatAgentClient, streamAppService, waitAppService,
                workspaceClient, directGate());
    }

    private static ChatAgentResumeGate directGate() {
        return new ChatAgentResumeGate(Runnable::run);
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

    // ---------- #39：静默轮（平台内部轻调用，无流桥） ----------

    @Test
    void given_silent_command_when_converse_silently_then_no_frame_published() {
        // 取名等平台内部轻调用：不进 agent 流通道（无 SSE 帧、不落等待点、无终态联动）
        ChatAgentCommand command = new ChatAgentCommand("run-n", "给项目取名", null, null,
                "naming-1", null, null, null, Map.of());
        when(chatAgentClient.converse(eq(command), any()))
                .thenReturn(new ChatAgentReply("run-n", "品牌官网"));
        ChatAgentAppService appService = appService();

        ChatAgentReply reply = appService.converseSilently(command);

        assertThat(reply.text()).isEqualTo("品牌官网");
        // 适配器侧 sink 被喂满过程帧也不外发（丢弃式 sink）
        verify(chatAgentClient).converse(eq(command), sinkCaptor.capture());
        sinkCaptor.getValue().accept(new AgentEvent(AgentEventTypes.TASK_START,
                Map.of("runId", "run-n")));
        sinkCaptor.getValue().accept(new AgentEvent(AgentEventTypes.WAIT_RAISED,
                Map.of("runId", "run-n")));
        verifyNoInteractions(streamAppService, waitAppService);
    }

    // ---------- #40：异步轮入口（编排层快返回 + 会话串行） ----------

    @Test
    void given_closed_gate_when_converse_async_then_new_run_reopens_and_runs() {
        // deny cap 关闸后用户开新一轮对话（新 run 承接会话）即复活——闸语义（#48）
        ChatAgentResumeGate gate = directGate();
        ChatAgentCommand command = new ChatAgentCommand("run-a", "继续聊", null, null,
                "s-a", "alice", null, null, Map.of());
        when(chatAgentClient.converse(eq(command), any()))
                .thenReturn(new ChatAgentReply("run-a", "好的"));
        ChatAgentAppService appService = new ChatAgentAppService(chatAgentClient,
                streamAppService, waitAppService, workspaceClient, gate);
        gate.close("s-a");

        appService.converseAsync(command);

        // 提交前先复活（client 内部的复活在排队任务里才跑——来不及），轮照常执行
        verify(chatAgentClient).converse(eq(command), any());
    }

    @Test
    void given_converse_failure_when_converse_async_then_not_propagated() {
        // 异步轮失败不炸编排调用方：error 帧已进流桥（client 内），异常由闸吞掉记日志
        ChatAgentCommand command = new ChatAgentCommand("run-b", "再问一轮", null, null,
                "s-b", "alice", null, null, Map.of());
        when(chatAgentClient.converse(eq(command), any()))
                .thenThrow(new DomainException(ChatAgentMessage.CONVERSE_FAILED, "模型超时"));
        ChatAgentAppService appService = new ChatAgentAppService(chatAgentClient,
                streamAppService, waitAppService, workspaceClient, directGate());

        assertThatCode(() -> appService.converseAsync(command)).doesNotThrowAnyException();
        verify(chatAgentClient).converse(eq(command), any());
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
