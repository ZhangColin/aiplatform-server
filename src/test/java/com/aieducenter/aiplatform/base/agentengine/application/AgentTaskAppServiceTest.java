package com.aieducenter.aiplatform.base.agentengine.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentTaskDispatchCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentTaskResponse;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentSession;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.RunResult;
import com.aieducenter.aiplatform.base.agentengine.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.agentengine.domain.port.CodingAgentAdapter;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentSessionRepository;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient;
import com.aieducenter.aiplatform.base.eventhub.domain.model.EventEnvelope;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.RecordingSseSender;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseChannelHub;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseServerEvent;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务编排用例（票 #20 + #21 等待点接线）：runId 生成、引擎路由、会话登记/续跑校验、
 * 计量归属兜底（subject=workspaceId）、agent 流桥（事件补 workspaceId 后透传通道）；
 * 片2b——复用会话前清理残留等待点、wait-raised 落库不透传、run 终态联动 EXPIRED。
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskAppServiceTest {

    private static final long WORKSPACE_ID = 4242L;
    private static final WorkspaceHandle HANDLE =
            WorkspaceHandle.dev(new WorkspaceId(WORKSPACE_ID), "ws-1", "net-1", 4096, 0);

    @Mock
    private AgentSessionRepository sessionRepository;
    @Mock
    private AgentWaitAppService waitAppService;

    private final FakeWorkspaceHandleClient handleClient = new FakeWorkspaceHandleClient();
    private final StubAdapter stubAdapter = new StubAdapter();
    private final RecordingSseSender sender = new RecordingSseSender();
    private SseChannelHub hub;
    private AgentTaskAppService appService;

    @BeforeEach
    void setUp() {
        hub = new SseChannelHub(sender, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                Duration.ofSeconds(600));
        appService = new AgentTaskAppService(handleClient,
                new AgentEngineRegistry(java.util.List.of(stubAdapter, new DshStubAdapter())),
                sessionRepository, new AgentStreamAppService(hub), waitAppService);
    }

    @AfterEach
    void tearDown() {
        hub.shutdown();
    }

    @Test
    void given_new_task_when_dispatch_then_run_id_generated_session_opened_stream_bridged() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_new", true);
        when(sessionRepository.findBySessionId("ses_new")).thenReturn(Optional.empty());

        AgentTaskResponse response = appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", "你是 BA", null, null, null));

        assertThat(response.accepted()).isTrue();
        assertThat(response.engine()).isEqualTo("opencode");
        assertThat(response.runId()).isNotBlank();
        assertThat(response.sessionId()).isEqualTo("ses_new");
        // 命令透传：runId 生成、sessionId 复用缝为空、计量归属兜底 subject=workspaceId
        AgentTaskCommand sent = stubAdapter.received.get(0);
        assertThat(sent.runId()).isEqualTo(response.runId());
        assertThat(sent.sessionId()).isNull();
        assertThat(sent.usageContext().subject()).isEqualTo(Long.toString(WORKSPACE_ID));
        // 会话登记：新开（open）并落库
        ArgumentCaptor<AgentSession> saved = ArgumentCaptor.forClass(AgentSession.class);
        verify(sessionRepository).save(saved.capture());
        assertThat(saved.getValue().getSessionId()).isEqualTo("ses_new");
        assertThat(saved.getValue().getLastRunId()).isEqualTo(response.runId());
    }

    @Test
    void given_adapter_events_when_dispatch_then_stream_payload_carries_workspace_id() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_new", true);
        when(sessionRepository.findBySessionId("ses_new")).thenReturn(Optional.empty());
        var emitter = appServiceDelegate().subscribe(null, null, null);

        appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", null, null, null, null));

        // 流桥：适配器事件 payload（已带 runId）补 workspaceId 后进 agent 流通道
        assertThat(sender.eventFramesOf(emitter)).hasSize(2);
        SseServerEvent first = sender.eventFramesOf(emitter).get(0);
        assertThat(first.id()).startsWith(capturedRunId() + ":1");
        Map<String, Object> payload = envelopePayload(first);
        assertThat(payload).containsEntry("workspaceId", Long.toString(WORKSPACE_ID));
        assertThat(payload).containsKey("runId");
        assertThat(payload).doesNotContainKey("type"); // payload 顶层禁 type 键名
    }

    @Test
    void given_reuse_session_when_dispatch_then_validated_touched_and_stale_waits_cleaned() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_exist", true);
        AgentSession existing = AgentSession.open(WORKSPACE_ID, "opencode", "ses_exist", "run-old");
        when(sessionRepository.findBySessionId("ses_exist"))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.of(existing));

        AgentTaskResponse response = appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("继续写", null, null, null, "ses_exist"));

        assertThat(response.sessionId()).isEqualTo("ses_exist");
        assertThat(stubAdapter.received.get(0).sessionId()).isEqualTo("ses_exist"); // 复用缝透传
        // 续跑刷新最近运行（同聚合行 ranOn，不新开）
        verify(sessionRepository).save(existing);
        assertThat(existing.getLastRunId()).isEqualTo(response.runId());
        // 片2b：复用前清理该会话残留等待点（「有则先清理再跑」）
        verify(waitAppService).cancelSessionWaits("ses_exist");
    }

    @Test
    void given_unknown_session_when_reuse_then_404() {
        when(sessionRepository.findBySessionId("ses_none")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("继续写", null, null, null, "ses_none")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.SESSION_NOT_FOUND.message());
    }

    @Test
    void given_session_of_other_workspace_when_reuse_then_409() {
        AgentSession foreign = AgentSession.open(9999L, "opencode", "ses_foreign", "run-old");
        when(sessionRepository.findBySessionId("ses_foreign")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("继续写", null, null, null, "ses_foreign")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.SESSION_WORKSPACE_MISMATCH.message());
    }

    @Test
    void given_unknown_engine_when_dispatch_then_404() {
        assertThatThrownBy(() -> appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", null, null, "codex", null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.ENGINE_NOT_FOUND.message());
    }

    @Test
    void given_rejected_run_when_dispatch_then_no_session_recorded() {
        stubAdapter.nextResult = RunResult.rejected("ignored");

        AgentTaskResponse response = appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", null, null, null, null));

        assertThat(response.accepted()).isFalse();
        assertThat(response.sessionId()).isNull();
        org.mockito.Mockito.verifyNoInteractions(sessionRepository);
    }

    @Test
    void given_dsh_reuse_when_engine_returns_new_session_then_new_row_opened() {
        // dsh 续跑 = 新一次性任务：适配器返回新 sessionId → 新登记（不 touch 旧行）
        DshStubAdapter dsh = new DshStubAdapter();
        dsh.nextSessionId = "dsh-new";
        AgentTaskAppService dshService = new AgentTaskAppService(handleClient,
                new AgentEngineRegistry(java.util.List.of(stubAdapter, dsh)),
                sessionRepository, new AgentStreamAppService(hub), waitAppService);
        AgentSession existing = AgentSession.open(WORKSPACE_ID, "dsh", "dsh-old", "run-old");
        when(sessionRepository.findBySessionId("dsh-old")).thenReturn(Optional.of(existing));
        when(sessionRepository.findBySessionId("dsh-new")).thenReturn(Optional.empty());

        AgentTaskResponse response = dshService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("继续写", null, null, "dsh", "dsh-old"));

        assertThat(response.sessionId()).isEqualTo("dsh-new");
        ArgumentCaptor<AgentSession> saved = ArgumentCaptor.forClass(AgentSession.class);
        verify(sessionRepository).save(saved.capture());
        assertThat(saved.getValue().getSessionId()).isEqualTo("dsh-new");
    }

    @Test
    void given_no_reuse_when_dispatch_then_no_wait_cleanup() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_new", true);
        when(sessionRepository.findBySessionId("ses_new")).thenReturn(Optional.empty());

        appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", null, null, null, null));

        // 新会话下发无残留可清理，不触发等待点清理
        verify(waitAppService, never()).cancelSessionWaits(any());
    }

    @Test
    void given_wait_raised_event_when_streamed_then_persisted_and_not_forwarded() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_new", true);
        stubAdapter.emitWaitRaised = true;
        when(sessionRepository.findBySessionId("ses_new")).thenReturn(Optional.empty());
        var emitter = appServiceDelegate().subscribe(null, null, null);

        appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", null, null, null, null));

        // 落库：wait-raised 载荷交给等待点用例（SSE 发射归 #22，底座不透传）
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(waitAppService).raiseFromEvent(eq(WORKSPACE_ID), payload.capture());
        assertThat(payload.getValue()).containsEntry("engineRef", "que_1")
                .containsEntry("kind", "QUESTION");
        // 通道只见平台两帧（task-start/task-finish），无 wait-raised 半成品事件透传
        assertThat(sender.eventFramesOf(emitter)).extracting(
                        frame -> ((EventEnvelope) frame.data()).type())
                .containsExactly("task-start", "task-finish");
    }

    @Test
    void given_run_terminal_event_when_streamed_then_pending_waits_expired() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_new", true);
        when(sessionRepository.findBySessionId("ses_new")).thenReturn(Optional.empty());

        appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", null, null, null, null));

        // run 终态（task-finish，超时/失败路径走 error 同拦）：其 PENDING 等待点联动 EXPIRED
        verify(waitAppService).expireRun(capturedRunId());
    }

    // ---------- 片5 编排入口（AgentRunContext） ----------

    @Test
    void given_run_context_when_dispatch_then_business_run_id_and_usage_context_honored() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_new", true);
        when(sessionRepository.findBySessionId("ses_new")).thenReturn(Optional.empty());

        AgentTaskResponse response = appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", "你是 BA", "model-x", null, null),
                new AgentRunContext("run-biz-1",
                        new UsageContext("proj-9", Map.of("role", "BA")),
                        Map.of("projectId", "proj-9")));

        // 业务侧 runId / 计量归属被尊重（不覆盖、不兜底）
        assertThat(response.runId()).isEqualTo("run-biz-1");
        AgentTaskCommand sent = stubAdapter.received.get(0);
        assertThat(sent.runId()).isEqualTo("run-biz-1");
        assertThat(sent.usageContext().subject()).isEqualTo("proj-9");
        assertThat(sent.usageContext().dims()).containsEntry("role", "BA");
    }

    @Test
    void given_run_context_without_usage_when_dispatch_then_neutral_fallback() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_new", true);
        when(sessionRepository.findBySessionId("ses_new")).thenReturn(Optional.empty());

        appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", null, null, null, null),
                new AgentRunContext("run-biz-1", null, Map.of()));

        // UsageContext 缺省 → subject=workspaceId 兜底；runId 仍用业务侧值
        AgentTaskCommand sent = stubAdapter.received.get(0);
        assertThat(sent.runId()).isEqualTo("run-biz-1");
        assertThat(sent.usageContext().subject()).isEqualTo(Long.toString(WORKSPACE_ID));
    }

    @Test
    void given_correlated_context_when_wait_raised_then_persisted_and_published_with_wait_id() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_new", true);
        stubAdapter.emitWaitRaised = true;
        when(sessionRepository.findBySessionId("ses_new")).thenReturn(Optional.empty());
        when(waitAppService.raiseFromEvent(eq(WORKSPACE_ID), any())).thenReturn(
                new WaitPointResponse("wait-1", Long.toString(WORKSPACE_ID), "ses_new",
                        "run-biz-1", "que_1", WaitKind.QUESTION, null, WaitStatus.PENDING, null,
                        "用哪个框架?", Map.of(), null, null, Instant.EPOCH, null));
        var emitter = appServiceDelegate().subscribe(null, null, null);

        appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", null, null, null, null),
                new AgentRunContext("run-biz-1", new UsageContext("proj-9", Map.of()),
                        Map.of("projectId", "proj-9")));

        // 带关联字段的编排调用方：wait-raised 落库后补发（帧序 task-start → wait-raised → task-finish）
        assertThat(sender.eventFramesOf(emitter)).extracting(
                        frame -> ((EventEnvelope) frame.data()).type())
                .containsExactly("task-start", "wait-raised", "task-finish");
        Map<String, Object> waitPayload = envelopePayload(sender.eventFramesOf(emitter).get(1));
        assertThat(waitPayload).containsEntry("waitId", "wait-1")
                .containsEntry("projectId", "proj-9")
                .containsEntry("workspaceId", Long.toString(WORKSPACE_ID));
    }

    @Test
    void given_uncorrelated_context_when_wait_raised_then_not_published() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_new", true);
        stubAdapter.emitWaitRaised = true;
        when(sessionRepository.findBySessionId("ses_new")).thenReturn(Optional.empty());
        var emitter = appServiceDelegate().subscribe(null, null, null);

        appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", null, null, null, null),
                new AgentRunContext("run-biz-1", null, Map.of()));

        // 关联字段为空 = 中性调用方：wait-raised 落库不透传（底座端点行为不变）
        verify(waitAppService).raiseFromEvent(eq(WORKSPACE_ID), any());
        assertThat(sender.eventFramesOf(emitter)).extracting(
                        frame -> ((EventEnvelope) frame.data()).type())
                .containsExactly("task-start", "task-finish");
    }

    @Test
    void given_correlated_context_when_frames_published_then_all_carry_correlation() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_new", true);
        when(sessionRepository.findBySessionId("ses_new")).thenReturn(Optional.empty());
        var emitter = appServiceDelegate().subscribe(null, null, null);

        appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", null, null, null, null),
                new AgentRunContext("run-biz-1", new UsageContext("proj-9", Map.of()),
                        Map.of("projectId", "proj-9")));

        // 每帧都注入关联字段（SSE事件清单·通道二：projectId 业务桥接注入）
        assertThat(sender.eventFramesOf(emitter)).hasSize(2);
        assertThat(sender.eventFramesOf(emitter)).allSatisfy(frame ->
                assertThat(envelopePayload(frame)).containsEntry("projectId", "proj-9"));
    }

    @Test
    void given_correlation_with_type_key_when_construct_context_then_rejected() {
        // 信封契约：payload 顶层禁 type 键名——构造期 fail-fast
        assertThatThrownBy(() -> new AgentRunContext("run-biz-1", null,
                Map.of("type", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 编排事件观察者（#27 修复编排链的 sink 缝） ----------

    @Test
    void given_event_observer_when_dispatch_then_frames_relayed_after_base_handling() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_new", true);
        when(sessionRepository.findBySessionId("ses_new")).thenReturn(Optional.empty());
        CopyOnWriteArrayList<String> observed = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> baseHandledBeforeObserver = new CopyOnWriteArrayList<>();

        appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", null, null, null, null),
                new AgentRunContext("run-biz-1", null, Map.of()),
                event -> {
                    observed.add(event.type());
                    if (AgentEventTypes.TASK_FINISH.equals(event.type())) {
                        // 观察者在底座桥之后收帧：run 终态等待点联动已发生
                        verify(waitAppService).expireRun(event.payload().get("runId").toString());
                        baseHandledBeforeObserver.add(event.payload().get("runId").toString());
                    }
                });

        // 适配器全帧同序回调（含过程帧与终态帧——编排方自行裁决）
        assertThat(observed).containsExactly("task-start", "task-finish");
        assertThat(baseHandledBeforeObserver).containsExactly("run-biz-1");
    }

    @Test
    void given_throwing_observer_when_dispatch_then_base_bridge_unaffected() {
        stubAdapter.nextResult = new RunResult("ignored", "ses_new", true);
        when(sessionRepository.findBySessionId("ses_new")).thenReturn(Optional.empty());
        var emitter = appServiceDelegate().subscribe(null, null, null);

        appService.dispatch(Long.toString(WORKSPACE_ID),
                new AgentTaskDispatchCommand("写个落地页", null, null, null, null),
                new AgentRunContext("run-biz-1", null, Map.of()),
                event -> {
                    throw new IllegalStateException("编排方链内异常");
                });

        // 观察者异常不拖垮底座流桥：SSE 两帧照发、无异常外抛
        assertThat(sender.eventFramesOf(emitter)).extracting(
                        frame -> ((EventEnvelope) frame.data()).type())
                .containsExactly("task-start", "task-finish");
        verify(waitAppService).expireRun("run-biz-1");
    }

    // ---------- 替身与工具 ----------

    /** 通道订阅代理（emitter 断言用）。 */
    private AgentStreamAppService appServiceDelegate() {
        return new AgentStreamAppService(hub);
    }

    private String capturedRunId() {
        return stubAdapter.received.get(0).runId();
    }

    private Map<String, Object> envelopePayload(SseServerEvent event) {
        EventEnvelope envelope = (EventEnvelope) event.data();
        return envelope.payload();
    }

    private static final class FakeWorkspaceHandleClient implements WorkspaceHandleClient {

        @Override
        public WorkspaceHandle handleOf(String workspaceId) {
            return HANDLE;
        }
    }

    /** 引擎替身：发射两帧事件 + 可注入的同步结果（wait-raised 可选加发）。 */
    private static class StubAdapter implements CodingAgentAdapter {

        RunResult nextResult = RunResult.rejected("ignored");
        volatile boolean emitWaitRaised;
        final CopyOnWriteArrayList<AgentTaskCommand> received = new CopyOnWriteArrayList<>();

        @Override
        public String engine() {
            return "opencode";
        }

        @Override
        public String label() {
            return "Stub";
        }

        @Override
        public String note() {
            return "测试替身";
        }

        @Override
        public boolean supportsQuestions() {
            return true;
        }

        @Override
        public boolean supportsPermissions() {
            return true;
        }

        @Override
        public RunResult runTask(WorkspaceHandle handle, AgentTaskCommand command,
                                 Consumer<AgentEvent> sink) {
            received.add(command);
            sink.accept(new AgentEvent(com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes.TASK_START,
                    Map.of("runId", command.runId(), "prompt", command.prompt())));
            if (emitWaitRaised) {
                // 片2b 发现通道形态：wait-raised 平台事件（引擎载荷进 data）
                sink.accept(new AgentEvent(
                        com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes.WAIT_RAISED,
                        Map.of("runId", command.runId(), "sessionId", "ses_new",
                                "kind", "QUESTION", "summary", "用哪个框架?",
                                "engineRef", "que_1",
                                "data", Map.of("id", "que_1"))));
            }
            sink.accept(new AgentEvent(com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes.TASK_FINISH,
                    Map.of("runId", command.runId(), "finish", "end")));
            return nextResult.sessionId() == null
                    ? nextResult : new RunResult(command.runId(), nextResult.sessionId(), true);
        }

        @Override
        public java.util.List<Map<String, Object>> pendingQuestions(WorkspaceHandle handle,
                                                                    String sessionId) {
            return java.util.List.of();
        }

        @Override
        public void replyQuestions(WorkspaceHandle handle, String sessionId, String requestId,
                                   java.util.List<java.util.List<String>> answers) {
        }

        @Override
        public void replyPermission(WorkspaceHandle handle, String sessionId,
                                    String permissionId, boolean approve) {
        }

        @Override
        public boolean abort(WorkspaceHandle handle, String sessionId) {
            return false;
        }

        @Override
        public boolean health(WorkspaceHandle handle) {
            return true;
        }
    }

    /** dsh 形态替身：忽略复用、恒返回新 sessionId（headless 一次性）。 */
    private static final class DshStubAdapter extends StubAdapter {

        String nextSessionId = "dsh-sid";

        @Override
        public String engine() {
            return "dsh";
        }

        @Override
        public RunResult runTask(WorkspaceHandle handle, AgentTaskCommand command,
                                 Consumer<AgentEvent> sink) {
            received.add(command);
            sink.accept(new AgentEvent(com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes.TASK_START,
                    Map.of("runId", command.runId(), "prompt", command.prompt())));
            sink.accept(new AgentEvent(com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes.TASK_FINISH,
                    Map.of("runId", command.runId(), "finish", "end")));
            return new RunResult(command.runId(), nextSessionId, true);
        }
    }
}
