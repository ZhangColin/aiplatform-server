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
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentSession;
import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.RunResult;
import com.aieducenter.aiplatform.base.agentengine.domain.port.CodingAgentAdapter;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentSessionRepository;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.RecordingSseSender;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseChannelHub;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseServerEvent;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务编排用例（票 #20）：runId 生成、引擎路由、会话登记/续跑校验、计量归属兜底
 * （subject=workspaceId）、agent 流桥（事件补 workspaceId 后透传通道）。
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskAppServiceTest {

    private static final long WORKSPACE_ID = 4242L;
    private static final WorkspaceHandle HANDLE =
            WorkspaceHandle.dev(new WorkspaceId(WORKSPACE_ID), "ws-1", "net-1", 4096, 0);

    @Mock
    private AgentSessionRepository sessionRepository;

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
                sessionRepository, new AgentStreamAppService(hub));
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
    void given_reuse_session_when_dispatch_then_validated_and_touched() {
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
                sessionRepository, new AgentStreamAppService(hub));
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

    // ---------- 替身与工具 ----------

    /** 通道订阅代理（emitter 断言用）。 */
    private AgentStreamAppService appServiceDelegate() {
        return new AgentStreamAppService(hub);
    }

    private String capturedRunId() {
        return stubAdapter.received.get(0).runId();
    }

    private Map<String, Object> envelopePayload(SseServerEvent event) {
        com.aieducenter.aiplatform.base.eventhub.domain.model.EventEnvelope envelope =
                (com.aieducenter.aiplatform.base.eventhub.domain.model.EventEnvelope) event.data();
        return envelope.payload();
    }

    private static final class FakeWorkspaceHandleClient implements WorkspaceHandleClient {

        @Override
        public WorkspaceHandle handleOf(String workspaceId) {
            return HANDLE;
        }
    }

    /** 引擎替身：发射两帧事件 + 可注入的同步结果。 */
    private static class StubAdapter implements CodingAgentAdapter {

        RunResult nextResult = RunResult.rejected("ignored");
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
