package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.chatagent.domain.error.ChatAgentMessage;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentWorkspace;
import com.aieducenter.aiplatform.base.chatagent.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentSessionRecorder;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentWorkspaceClient;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.cartisan.core.exception.DomainException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

/**
 * {@link AgentscopeChatAgentClient}（#44 计量/上下文 + #45 事件桥/工作区解析）：
 * 流帧序（task-start → session-created → 过程帧 → task-finish / error）、文本增量
 * 汇聚、RuntimeContext 组装、模型调用事件 → UsageEvent 恰一条、workspaceId →
 * 项目 dev 工作区。
 */
@ExtendWith(MockitoExtension.class)
class AgentscopeChatAgentClientTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AgentscopeHarnessAgentFactory factory;

    @Mock
    private HarnessAgent agent;

    @Mock
    private ChatAgentWorkspaceClient workspaceClient;

    @Mock
    private UsageEventSink usageEventSink;

    @Mock
    private ChatAgentSessionRecorder sessionRecorder;

    @Captor
    private ArgumentCaptor<UsageEvent> usageCaptor;

    @Captor
    private ArgumentCaptor<RuntimeContext> contextCaptor;

    private ChatAgentProperties properties;

    private AgentscopeChatAgentClient client;

    @BeforeEach
    void setUp() {
        properties = new ChatAgentProperties();
        properties.setAgentName("chat-agent");
        properties.setDefaultModel("deepseek:deepseek-v4-flash");
        properties.setDefaultSystemPrompt("你是平台对话智能体。");
        properties.setTimeout(Duration.ofSeconds(30));
        client = new AgentscopeChatAgentClient(factory, properties, workspaceClient,
                sessionRecorder, usageEventSink, new ChatAgentResumeGate(Runnable::run), CLOCK);
    }

    private ChatAgentCommand command(String modelString, UsageContext usage) {
        return new ChatAgentCommand("run-1", "你好", null, modelString, "s-1", "alice",
                usage, null, Map.of());
    }

    private void givenStream(io.agentscope.core.event.AgentEvent... events) {
        when(factory.obtain(any(), any(), any(), any())).thenReturn(agent);
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class)))
                .thenReturn(Flux.fromIterable(List.of(events)));
    }

    @Test
    void given_streaming_text_deltas_when_converse_then_lifecycle_and_text_frames_in_order() {
        givenStream(
                new TextBlockDeltaEvent("r-1", "b-1", "你"),
                new TextBlockDeltaEvent("r-1", "b-1", "好"),
                new TextBlockDeltaEvent("r-1", "b-1", "呀"));

        List<AgentEvent> frames = new ArrayList<>();
        var reply = client.converse(command(null, null), frames::add);

        assertThat(frames.stream().map(AgentEvent::type)).containsExactly(
                AgentEventTypes.TASK_START, AgentEventTypes.SESSION_CREATED,
                "text", "text", "text", AgentEventTypes.TASK_FINISH);
        // 开场帧形状
        assertThat(frames.get(0).payload()).containsOnly(
                Map.entry("runId", "run-1"), Map.entry("prompt", "你好"),
                Map.entry("model", "deepseek:deepseek-v4-flash"), Map.entry("engine", "agentscope"));
        assertThat(frames.get(1).payload()).containsOnly(
                Map.entry("runId", "run-1"), Map.entry("sessionId", "s-1"),
                Map.entry("engine", "agentscope"));
        // 文本增量帧：runId 锚定 + delta 在 data 内层
        assertThat(frames.get(2).payload()).containsAllEntriesOf(Map.of(
                "runId", "run-1", "sessionId", "s-1", "engine", "agentscope"));
        assertThat(frames.get(2).payload().get("data"))
                .isEqualTo(Map.of("delta", "你", "blockId", "b-1"));
        // 收口帧
        assertThat(frames.get(5).payload()).containsEntry("finish", "end");
        // 回复文本 = 增量拼接（#44 口径不变）
        assertThat(reply.runId()).isEqualTo("run-1");
        assertThat(reply.text()).isEqualTo("你好呀");
    }

    @Test
    void given_same_session_second_run_when_converse_then_session_created_not_repeated() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "一"));
        client.converse(command(null, null), event -> {
        });

        givenStream(new TextBlockDeltaEvent("r-2", "b-1", "二"));
        List<AgentEvent> frames = new ArrayList<>();
        client.converse(command(null, null), frames::add);

        assertThat(frames.stream().map(AgentEvent::type)).containsExactly(
                AgentEventTypes.TASK_START, "text", AgentEventTypes.TASK_FINISH);
    }

    @Test
    void given_exceed_max_iters_when_converse_then_task_finish_carries_engine_finish_token() {
        givenStream(new ExceedMaxItersEvent("r-1", 10, 10));

        List<AgentEvent> frames = new ArrayList<>();
        client.converse(command(null, null), frames::add);

        AgentEvent finish = frames.get(frames.size() - 1);
        assertThat(finish.type()).isEqualTo(AgentEventTypes.TASK_FINISH);
        assertThat(finish.payload()).containsEntry("finish", "exceed_max_iters");
    }

    @Test
    void given_converse_when_call_agent_then_runtime_context_carries_session_and_user() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "嗯"));

        client.converse(command(null, null), event -> {
        });

        verify(agent).streamEvents(any(List.class), contextCaptor.capture());
        assertThat(contextCaptor.getValue().getSessionId()).isEqualTo("s-1");
        assertThat(contextCaptor.getValue().getUserId()).isEqualTo("alice");
    }

    @Test
    void given_workspace_id_when_converse_then_project_dev_workspace_resolved() {
        when(workspaceClient.handleOf("42")).thenReturn(WorkspaceHandle.dev(
                WorkspaceId.of("42"), "ws-42-dev", "net-42", 0, 0));
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "写"));

        client.converse(new ChatAgentCommand("run-9", "写 PRD", null, null, "s-9", "alice",
                null, "42", Map.of()), event -> {
                });

        verify(factory).obtain(eq("chat-agent"), any(), eq("deepseek:deepseek-v4-flash"),
                eq(new ChatAgentWorkspace.ProjectDev("42", "ws-42-dev")));
    }

    @Test
    void given_no_workspace_id_when_converse_then_local_workspace_fallback() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "本地"));

        client.converse(command(null, null), event -> {
        });

        verify(factory).obtain(any(), any(), any(),
                eq(new ChatAgentWorkspace.Local(properties.getWorkspace())));
        verifyNoInteractions(workspaceClient);
    }

    @Test
    void given_multiple_model_call_ends_when_converse_then_usage_summed_and_reported_once() {
        givenStream(
                new ModelCallEndEvent("r-1", new ChatUsage(100, 40, 20, 0.5)),
                new TextBlockDeltaEvent("r-1", "b-1", "答"),
                new ModelCallEndEvent("r-1", new ChatUsage(60, 10, 0, 0.2)));

        client.converse(command("deepseek:deepseek-chat",
                        new UsageContext("prj-1", Map.of("role", "BA"))),
                event -> {
                });

        verify(usageEventSink).report(usageCaptor.capture());
        UsageEvent event = usageCaptor.getValue();
        assertThat(event.eventId()).isEqualTo("chat-usage-run-1");
        assertThat(event.ts()).isEqualTo(Instant.parse("2026-08-25T10:00:00Z"));
        assertThat(event.subject()).isEqualTo("prj-1");
        assertThat(event.runId()).isEqualTo("run-1");
        assertThat(event.sessionId()).isEqualTo("s-1");
        assertThat(event.provider()).isEqualTo("deepseek");
        assertThat(event.model()).isEqualTo("deepseek-chat");
        assertThat(event.engine()).isEqualTo("agentscope");
        assertThat(event.dims()).containsEntry("role", "BA");
        assertThat(event.tokens().input()).isEqualTo(140);
        assertThat(event.tokens().output()).isEqualTo(50);
        assertThat(event.tokens().cacheRead()).isEqualTo(20);
        assertThat(event.tokens().total()).isPositive();
    }

    @Test
    void given_no_usage_context_when_converse_then_metering_skipped() {
        givenStream(new ModelCallEndEvent("r-1", new ChatUsage(10, 5, 0, 0.1)));

        client.converse(command(null, null), event -> {
        });

        verifyNoInteractions(usageEventSink);
    }

    @Test
    void given_no_model_call_end_when_converse_then_zero_usage_not_reported() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "空"));

        client.converse(command(null, new UsageContext("prj-1", Map.of())), event -> {
        });

        verifyNoInteractions(usageEventSink);
    }

    @Test
    void given_stream_error_when_converse_then_error_frame_then_domain_exception() {
        when(factory.obtain(any(), any(), any(), any())).thenReturn(agent);
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new RuntimeException("boom")));

        List<AgentEvent> frames = new ArrayList<>();
        assertThatThrownBy(() -> client.converse(command(null, null), frames::add))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ChatAgentMessage.CONVERSE_FAILED.message());

        assertThat(frames.stream().map(AgentEvent::type)).containsExactly(
                AgentEventTypes.TASK_START, AgentEventTypes.SESSION_CREATED,
                AgentEventTypes.ERROR);
        assertThat(frames.get(2).payload()).containsEntry("message", "boom");
    }

    @Test
    void given_startup_failure_when_converse_then_error_frame_then_exception_reraised() {
        // #52 触达补口：起跑失败（如缺 API key 致模型客户端构建抛 IllegalStateException）
        // 原是 runTurn 前的零帧区（续跑闸后台线程吞异常，用户只见死寂）——前段失败也经
        // sink 发 error 帧（runId 锚定 = command 的），异常照常上抛（吞不吞归续跑闸既有语义）
        when(factory.obtain(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("DeepSeek API key 未配置"));

        List<AgentEvent> frames = new ArrayList<>();
        assertThatThrownBy(() -> client.converse(command(null, null), frames::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API key");

        assertThat(frames.stream().map(AgentEvent::type))
                .containsExactly(AgentEventTypes.ERROR);
        assertThat(frames.get(0).payload()).containsEntry("runId", "run-1");
        assertThat(frames.get(0).payload())
                .containsEntry("message", "DeepSeek API key 未配置");
    }

    @Test
    void given_stream_error_after_model_call_when_converse_then_consumed_usage_still_reported() {
        when(factory.obtain(any(), any(), any(), any())).thenReturn(agent);
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class)))
                .thenReturn(Flux.concat(
                        Flux.just(new ModelCallEndEvent("r-1", new ChatUsage(100, 40, 0, 0.5))),
                        Flux.error(new RuntimeException("mid-stream boom"))));

        assertThatThrownBy(() -> client.converse(
                        command(null, new UsageContext("prj-1", Map.of())), event -> {
                        }))
                .isInstanceOf(DomainException.class);

        verify(usageEventSink).report(usageCaptor.capture());
        assertThat(usageCaptor.getValue().tokens().input()).isEqualTo(100);
        assertThat(usageCaptor.getValue().tokens().output()).isEqualTo(40);
    }

    @Test
    void given_no_model_string_when_converse_then_configured_default_applied() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "默认"));

        client.converse(command(null, null), event -> {
        });

        verify(factory).obtain(eq("chat-agent"), eq("你是平台对话智能体。"),
                eq("deepseek:deepseek-v4-flash"), any());
    }

    // ---------- #48：挂起语义 / resume / 会话持久判定 ----------

    @Test
    void given_confirm_event_when_converse_then_wait_raised_and_no_task_finish() {
        givenStream(
                new TextBlockDeltaEvent("r-1", "b-1", "需要确认一个操作："),
                new RequireUserConfirmEvent("reply-9", List.of(
                        new ToolUseBlock("tc-1", "write_file", Map.of("path", "docs/PRD.md")))));

        List<AgentEvent> frames = new ArrayList<>();
        client.converse(command(null, null), frames::add);

        // 挂起 = 软终点：wait-raised 发出（流桥落库成等待点），不发 task-finish
        assertThat(frames.stream().map(AgentEvent::type)).containsExactly(
                AgentEventTypes.TASK_START, AgentEventTypes.SESSION_CREATED,
                "text", AgentEventTypes.WAIT_RAISED);
        AgentEvent wait = frames.get(3);
        assertThat(wait.payload()).containsEntry(AgentEventTypes.WAIT_ENGINE_REF_FIELD, "reply-9");
        assertThat(wait.payload()).containsEntry(AgentEventTypes.WAIT_KIND_FIELD, "PERMISSION");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) wait.payload()
                .get(AgentEventTypes.WAIT_DATA_FIELD);
        assertThat(data.get("modelString")).isEqualTo("deepseek:deepseek-v4-flash");
        assertThat(data.get("userId")).isEqualTo("alice");
        assertThat(data.get("usageContext")).isEqualTo(Map.of());
    }

    @Test
    void given_resume_request_when_resume_then_confirm_results_in_metadata_and_finishes() {
        when(factory.obtain(any(), any(), any(), any())).thenReturn(agent);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> messages = ArgumentCaptor.forClass(List.class);
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new TextBlockDeltaEvent("r-2", "b-2", "续跑中")));

        List<AgentEvent> frames = new ArrayList<>();
        client.resume(new AgentscopeChatAgentClient.ChatAgentResume(
                "run-1", "s-1", "alice", null, "deepseek:deepseek-v4-flash", null, "reply-9",
                List.of(new ConfirmResult(true,
                        new ToolUseBlock("tc-1", "write_file", Map.of("path", "x")))),
                "approved", null, Map.of("projectId", "42")), frames::add);

        // 恢复消息带 ConfirmResult metadata（AgentScope 挂起恢复口）；续跑流正常收口
        verify(agent).streamEvents(messages.capture(), any(RuntimeContext.class));
        Msg resumeMsg = messages.getValue().get(0);
        assertThat(resumeMsg.getMetadata()
                .get(Msg.METADATA_CONFIRM_RESULTS)).isInstanceOf(List.class);
        assertThat(resumeMsg.getTextContent()).isEqualTo("approved");
        assertThat(frames.stream().map(AgentEvent::type)).containsExactly(
                "text", AgentEventTypes.TASK_FINISH);
        verify(factory).obtain(any(), any(), eq("deepseek:deepseek-v4-flash"), any());
    }

    @Test
    void given_resume_prepare_fails_when_resume_then_error_frame_emitted_and_rethrown() {
        // #52 同口径（converse 已补，resume 原是零帧区）：缺 API key 致模型创建失败等
        // 前段失败发生在续跑闸后台线程（异常被吞只记日志）——必须先发 error 帧
        //（runId 锚定）再上抛，否则用户侧死寂（真机 ba- 会话 settle 续跑事故）
        when(factory.obtain(any(), any(), any(), any())).thenThrow(new IllegalArgumentException(
                "Failed to create model for id: deepseek:deepseek-v4-flash: "
                        + "Environment variable DEEPSEEK_API_KEY is required to auto-create model"));

        List<AgentEvent> frames = new ArrayList<>();
        assertThatThrownBy(() -> client.resume(new AgentscopeChatAgentClient.ChatAgentResume(
                "run-1", "s-1", "alice", null, "deepseek:deepseek-v4-flash", null, "reply-9",
                List.of(new ConfirmResult(true,
                        new ToolUseBlock("tc-1", "write_file", Map.of("path", "x")))),
                "approved", null, Map.of("projectId", "42")), frames::add))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(frames.stream().map(AgentEvent::type))
                .containsExactly(AgentEventTypes.ERROR);
        assertThat(frames.get(0).payload())
                .containsEntry("runId", "run-1")
                .containsEntry("message",
                        "Failed to create model for id: deepseek:deepseek-v4-flash: "
                                + "Environment variable DEEPSEEK_API_KEY is required to auto-create model");
    }

    @Test
    void given_workspace_session_absent_when_converse_then_recorded_and_session_created() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "hi"));
        when(workspaceClient.handleOf("42")).thenReturn(WorkspaceHandle.dev(
                WorkspaceId.of("42"), "ws-42-dev", "net-42", 0, 0));
        when(sessionRecorder.recordIfAbsent("42", "s-1", "run-1")).thenReturn(true);
        ChatAgentCommand cmd = new ChatAgentCommand("run-1", "你好", null, null, "s-1",
                "alice", null, "42", Map.of());

        List<AgentEvent> frames = new ArrayList<>();
        client.converse(cmd, frames::add);

        verify(sessionRecorder).recordIfAbsent("42", "s-1", "run-1");
        assertThat(frames.stream().map(AgentEvent::type)).contains(
                AgentEventTypes.SESSION_CREATED);
    }

    @Test
    void given_workspace_session_already_recorded_when_converse_then_session_created_skipped() {
        // 跨重启会话行已存在（首见判定持久化）：不重发 session-created
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "hi"));
        when(workspaceClient.handleOf("42")).thenReturn(WorkspaceHandle.dev(
                WorkspaceId.of("42"), "ws-42-dev", "net-42", 0, 0));
        when(sessionRecorder.recordIfAbsent("42", "s-1", "run-2")).thenReturn(false);
        ChatAgentCommand cmd = new ChatAgentCommand("run-2", "继续", null, null, "s-1",
                "alice", null, "42", Map.of());

        List<AgentEvent> frames = new ArrayList<>();
        client.converse(cmd, frames::add);

        assertThat(frames.stream().map(AgentEvent::type))
                .doesNotContain(AgentEventTypes.SESSION_CREATED);
    }
}
