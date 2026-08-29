package com.aieducenter.aiplatform.base.agentengine.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.SettleResult;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentSession;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentWait;
import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.RunResult;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.model.WaitSettlement;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.agentengine.domain.port.CodingAgentAdapter;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentSessionRepository;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentWaitRepository;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 等待点用例（票 #21 验收主面）：raise 幂等与稳定 waitId、跨会话聚合、settle 三型
 * （校验链 404/409、引擎先送达后落库、deny cap 平台终止）、run 终态联动 EXPIRED、
 * 复用会话残留清理 CANCELLED。
 */
@ExtendWith(MockitoExtension.class)
class AgentWaitAppServiceTest {

    private static final long WORKSPACE_ID = 4242L;
    private static final String WORKSPACE = Long.toString(WORKSPACE_ID);
    private static final Instant NOW = Instant.parse("2026-08-22T08:00:00Z");

    @Mock
    private AgentWaitRepository waitRepository;
    @Mock
    private AgentSessionRepository sessionRepository;

    private final FakeWorkspaceHandleClient handleClient = new FakeWorkspaceHandleClient();
    private final RecordingAdapter adapter = new RecordingAdapter();
    private AgentWaitAppService appService;

    @BeforeEach
    void setUp() {
        appService = new AgentWaitAppService(waitRepository, sessionRepository,
                new WaitResponderDirectory(List.of(adapter), List.of()), handleClient, 3,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---------- raise ----------

    @Test
    void given_new_wait_when_raise_then_persisted_with_stable_wait_id() {
        when(waitRepository.findBySessionIdAndEngineRefAndStatus(
                "ses_1", "que_1", WaitStatus.PENDING)).thenReturn(Optional.empty());
        when(waitRepository.save(any(AgentWait.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WaitPointResponse response = appService.raise(WORKSPACE_ID, "ses_1", "run-1",
                WaitKind.QUESTION, "que_1", "用哪个框架?", Map.of("id", "que_1"));

        assertThat(response.waitId()).isNotBlank();
        assertThat(response.status()).isEqualTo(WaitStatus.PENDING);
        assertThat(response.kind()).isEqualTo(WaitKind.QUESTION);
        assertThat(response.raisedAt()).isEqualTo(NOW);
        // 稳定标识：登记即定，后续任何读面同值
        ArgumentCaptor<AgentWait> saved = ArgumentCaptor.forClass(AgentWait.class);
        verify(waitRepository).save(saved.capture());
        assertThat(saved.getValue().getWaitId()).isEqualTo(response.waitId());
    }

    @Test
    void given_pending_row_of_same_ref_when_raise_then_idempotent_return_existing() {
        AgentWait existing = AgentWait.raise(WORKSPACE_ID, "ses_1", "run-1",
                WaitKind.QUESTION, "que_1", null, null, NOW);
        when(waitRepository.findBySessionIdAndEngineRefAndStatus(
                "ses_1", "que_1", WaitStatus.PENDING)).thenReturn(Optional.of(existing));

        WaitPointResponse response = appService.raise(WORKSPACE_ID, "ses_1", "run-9",
                WaitKind.QUESTION, "que_1", null, null);

        // 同挂起重复上报（watcher 重连/轮询重叠）收敛到既有 PENDING 行
        assertThat(response.waitId()).isEqualTo(existing.getWaitId());
        verify(waitRepository, never()).save(any());
    }

    @Test
    void given_terminal_row_of_same_ref_when_raise_then_new_pending_row_registered() {
        // 引擎侧挂起在终态联动/复用清理后仍活着（超时误伤、清理后引擎重检到）：
        // 终态行不挡路——登记新 PENDING 行（demo 病「重启后看得见答不了」对策）
        AgentWait expired = AgentWait.raise(WORKSPACE_ID, "ses_1", "run-1",
                WaitKind.QUESTION, "que_1", null, null, NOW);
        expired.expire(NOW);
        when(waitRepository.findBySessionIdAndEngineRefAndStatus(
                "ses_1", "que_1", WaitStatus.PENDING)).thenReturn(Optional.empty());
        when(waitRepository.save(any(AgentWait.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WaitPointResponse response = appService.raise(WORKSPACE_ID, "ses_1", "run-2",
                WaitKind.QUESTION, "que_1", null, null);

        assertThat(response.waitId()).isNotEqualTo(expired.getWaitId());
        assertThat(response.status()).isEqualTo(WaitStatus.PENDING);
        assertThat(response.runId()).isEqualTo("run-2");
        verify(waitRepository).save(any(AgentWait.class));
    }

    @Test
    void given_wait_raised_stream_event_when_raiseFromEvent_then_mapped_and_persisted() {
        when(waitRepository.findBySessionIdAndEngineRefAndStatus(
                "ses_1", "per_1", WaitStatus.PENDING)).thenReturn(Optional.empty());
        when(waitRepository.save(any(AgentWait.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WaitPointResponse response = appService.raiseFromEvent(WORKSPACE_ID, Map.of(
                "runId", "run-1",
                "sessionId", "ses_1",
                "kind", "PERMISSION",
                "summary", "执行 rm -rf /tmp",
                "engineRef", "per_1",
                "data", Map.of("id", "per_1", "title", "执行 rm -rf /tmp")));

        assertThat(response.kind()).isEqualTo(WaitKind.PERMISSION);
        assertThat(response.summary()).isEqualTo("执行 rm -rf /tmp");
        assertThat(response.engineRef()).isEqualTo("per_1");
        assertThat(response.body()).containsEntry("id", "per_1");
    }

    // ---------- 聚合查询 ----------

    @Test
    void given_waits_across_sessions_when_pendingWaits_then_workspace_pending_only() {
        AgentWait a = AgentWait.raise(WORKSPACE_ID, "ses_1", "run-1", WaitKind.QUESTION,
                "que_1", null, null, NOW);
        AgentWait b = AgentWait.raise(WORKSPACE_ID, "ses_2", "run-2", WaitKind.PERMISSION,
                "per_2", null, null, NOW);
        when(waitRepository.findByWorkspaceIdAndStatusOrderByRaisedAtDesc(
                WORKSPACE_ID, WaitStatus.PENDING)).thenReturn(List.of(b, a));

        List<WaitPointResponse> pending = appService.pendingWaits(WORKSPACE);

        assertThat(pending).extracting(WaitPointResponse::sessionId)
                .containsExactly("ses_2", "ses_1"); // 跨会话聚合（新者在前）
    }

    @Test
    void given_pending_waits_across_workspaces_when_listPendingWaits_then_all_newest_first() {
        // 工作台 AGENT_WAIT 投影源（A2 §4/§5）：跨项目全量 PENDING，新者在前
        AgentWait fresh = AgentWait.raise(4243L, "ses_9", "run-9", WaitKind.PERMISSION,
                "per_9", null, null, NOW);
        AgentWait older = AgentWait.raise(4242L, "ses_1", "run-1", WaitKind.QUESTION,
                "que_1", null, null, NOW);
        when(waitRepository.findByStatusOrderByRaisedAtDesc(WaitStatus.PENDING))
                .thenReturn(List.of(fresh, older));

        List<WaitPointResponse> pending = appService.listPendingWaits();

        assertThat(pending).extracting(WaitPointResponse::workspaceId)
                .containsExactly("4243", "4242"); // 跨项目（工作区不滤）
        assertThat(pending).extracting(WaitPointResponse::status)
                .containsOnly(WaitStatus.PENDING);
    }

    @Test
    void given_wait_id_when_wait_then_addressable_globally() {
        when(waitRepository.findById("wait_x")).thenReturn(Optional.of(
                AgentWait.raise(4242L, "ses_1", "run-1", WaitKind.QUESTION, "que_1",
                        null, null, NOW)));

        assertThat(appService.wait("wait_x")).isPresent();
        assertThat(appService.wait("wait_none")).isEmpty();
    }

    // ---------- settle：校验链 ----------

    @Test
    void given_answer_when_settle_then_engine_replied_first_then_settled() {
        AgentWait wait = raisedQuestion();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1"))
                .thenReturn(Optional.of(AgentSession.open(WORKSPACE_ID, "opencode",
                        "ses_1", "run-1")));

        appService.settle(WORKSPACE, new WaitSettlement.Answer(wait.getWaitId(),
                List.of(List.of("Vue3"))));

        // 引擎派发键 = engineRef（que_*），答案原样
        assertThat(adapter.repliedQuestions).containsExactly(
                new ReplyRecord("ses_1", "que_1", List.of(List.of("Vue3"))));
        // 落库关闭：引擎送达成功才 SETTLED（answer 语义）
        assertThat(wait.getStatus()).isEqualTo(WaitStatus.SETTLED);
        assertThat(wait.getSettleOutcome()).isEqualTo(WaitOutcome.ANSWERED);
        verify(waitRepository).save(wait);
    }

    @Test
    void given_unknown_wait_when_settle_then_404() {
        when(waitRepository.findById("wait_none")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appService.settle(WORKSPACE,
                new WaitSettlement.Deferred("wait_none", "转任务")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.WAIT_NOT_FOUND.message());
    }

    @Test
    void given_stale_wait_when_settle_then_409_conflict() {
        AgentWait settled = raisedQuestion();
        settled.settle(WaitOutcome.ANSWERED, NOW);
        when(waitRepository.findById(settled.getWaitId())).thenReturn(Optional.of(settled));

        assertThatThrownBy(() -> appService.settle(WORKSPACE,
                new WaitSettlement.Answer(settled.getWaitId(), List.of(List.of("A")))))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.WAIT_CONFLICT.message());
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void given_session_gone_when_settle_then_409_not_resumable() {
        AgentWait wait = raisedQuestion();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appService.settle(WORKSPACE,
                new WaitSettlement.Answer(wait.getWaitId(), List.of(List.of("A")))))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.WAIT_CONFLICT.message());
        // 未达引擎：会话不可续跑时不派发
        assertThat(adapter.repliedQuestions).isEmpty();
    }

    @Test
    void given_foreign_workspace_when_settle_then_409() {
        AgentWait wait = raisedQuestion();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));

        assertThatThrownBy(() -> appService.settle(Long.toString(9999L),
                new WaitSettlement.Deferred(wait.getWaitId(), null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.WAIT_CONFLICT.message());
    }

    @Test
    void given_engine_failure_when_settle_answer_then_502_and_stays_pending() {
        AgentWait wait = raisedQuestion();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1"))
                .thenReturn(Optional.of(AgentSession.open(WORKSPACE_ID, "opencode",
                        "ses_1", "run-1")));
        adapter.failReplies = true;

        assertThatThrownBy(() -> appService.settle(WORKSPACE,
                new WaitSettlement.Answer(wait.getWaitId(), List.of(List.of("A")))))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.ENGINE_REQUEST_FAILED.message());

        // 引擎未送达：保持 PENDING 可重试，不落库
        assertThat(wait.getStatus()).isEqualTo(WaitStatus.PENDING);
        verify(waitRepository, never()).save(any());
    }

    // ---------- settle：权限与 deny cap ----------

    @Test
    void given_permission_approve_when_settle_then_approved_without_abort() {
        AgentWait wait = raisedPermission();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1"))
                .thenReturn(Optional.of(AgentSession.open(WORKSPACE_ID, "opencode",
                        "ses_1", "run-1")));

        appService.settle(WORKSPACE, new WaitSettlement.PermissionDecision(
                wait.getWaitId(), true));

        assertThat(adapter.repliedPermissions).containsExactly(
                new PermissionRecord("ses_1", "per_1", true));
        assertThat(wait.getSettleOutcome()).isEqualTo(WaitOutcome.APPROVED);
        assertThat(adapter.aborts).isEmpty();
    }

    @Test
    void given_denies_below_cap_when_settle_deny_then_no_termination_signalled() {
        assertDenyCapping(2, 3, false);
    }

    @Test
    void given_denies_reach_cap_when_settle_deny_then_termination_signalled_to_caller() {
        // 票 #38：settle 只判不定——denyCapped=true 回报（含终止派发键 engine），
        // abort/收口/帧归调用方接续 AgentTaskAppService.terminateRun（该处已测）
        assertDenyCapping(3, 3, true);
    }

    private void assertDenyCapping(long totalDenies, int cap, boolean expectCapped) {
        AgentWaitAppService service = new AgentWaitAppService(waitRepository,
                sessionRepository,
                new WaitResponderDirectory(List.of(adapter), List.of()), handleClient,
                cap, Clock.fixed(NOW, ZoneOffset.UTC));
        AgentWait wait = raisedPermission();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1"))
                .thenReturn(Optional.of(AgentSession.open(WORKSPACE_ID, "opencode",
                        "ses_1", "run-1")));
        // 计数语义：本行 deny 已落库后的同 run deny 总数（含本行）
        when(waitRepository.countByRunIdAndStatusAndSettleOutcome(
                eq("run-1"), eq(WaitStatus.SETTLED), eq(WaitOutcome.DENIED)))
                .thenReturn(totalDenies);

        SettleResult result = service.settle(WORKSPACE,
                new WaitSettlement.PermissionDecision(wait.getWaitId(), false));

        assertThat(wait.getSettleOutcome()).isEqualTo(WaitOutcome.DENIED);
        assertThat(result.settled().waitId()).isEqualTo(wait.getWaitId());
        assertThat(result.engine()).isEqualTo("opencode"); // 终止派发键随结果回报
        assertThat(result.denyCapped()).isEqualTo(expectCapped);
        // 判定在底座、终止在调用方：settle 自身不 abort、不收口同 run 剩余等待点
        assertThat(adapter.aborts).isEmpty();
    }

    // ---------- settle：Deferred ----------

    @Test
    void given_deferred_when_settle_then_closed_without_engine_interaction() {
        AgentWait wait = raisedQuestion();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1"))
                .thenReturn(Optional.of(AgentSession.open(WORKSPACE_ID, "opencode",
                        "ses_1", "run-1")));

        appService.settle(WORKSPACE, new WaitSettlement.Deferred(wait.getWaitId(),
                "转测试任务"));

        assertThat(wait.getStatus()).isEqualTo(WaitStatus.SETTLED);
        assertThat(wait.getSettleOutcome()).isEqualTo(WaitOutcome.DEFERRED);
        // 纯平台侧关闭：不派发引擎（口子③：续跑是任务完成后的新消息）
        assertThat(adapter.repliedQuestions).isEmpty();
        assertThat(adapter.repliedPermissions).isEmpty();
    }

    // ---------- settle：REST 命令形态 ----------

    @Test
    void given_settle_command_when_settle_then_mapped_to_typed_settlement() {
        AgentWait wait = raisedPermission();
        when(waitRepository.findById(wait.getWaitId())).thenReturn(Optional.of(wait));
        when(sessionRepository.findBySessionId("ses_1"))
                .thenReturn(Optional.of(AgentSession.open(WORKSPACE_ID, "opencode",
                        "ses_1", "run-1")));

        appService.settle(WORKSPACE, wait.getWaitId(),
                new WaitSettleCommand(WaitSettleCommand.TYPE_PERMISSION, null, false, null));

        assertThat(adapter.repliedPermissions).containsExactly(
                new PermissionRecord("ses_1", "per_1", false));
        assertThat(wait.getSettleOutcome()).isEqualTo(WaitOutcome.DENIED);
    }

    @Test
    void given_answer_command_without_answers_when_settle_then_rejected() {
        // 400 面：BaseCodeMessage.BAD_REQUEST（detail 参数进 errors 数组，不进 message）
        assertThatThrownBy(() -> appService.settle(WORKSPACE, "wait_x",
                new WaitSettleCommand(WaitSettleCommand.TYPE_ANSWER, null, null, null)))
                .isInstanceOf(com.cartisan.core.exception.ApplicationException.class)
                .hasMessageContaining(
                        com.cartisan.core.exception.BaseCodeMessage.BAD_REQUEST.message());
        assertThatThrownBy(() -> appService.settle(WORKSPACE, "wait_x",
                new WaitSettleCommand("bogus", null, null, null)))
                .isInstanceOf(com.cartisan.core.exception.ApplicationException.class)
                .hasMessageContaining(
                        com.cartisan.core.exception.BaseCodeMessage.BAD_REQUEST.message());
    }

    // ---------- 终态联动与复用清理 ----------

    @Test
    void given_run_terminal_when_expireRun_then_pending_waits_expired() {
        AgentWait wait = raisedQuestion();
        when(waitRepository.findByRunIdAndStatus("run-1", WaitStatus.PENDING))
                .thenReturn(List.of(wait));
        when(waitRepository.transitionIfStatus(wait.getWaitId(), WaitStatus.PENDING,
                WaitStatus.EXPIRED, NOW)).thenReturn(1);

        int closed = appService.expireRun("run-1");

        assertThat(closed).isEqualTo(1);
        assertThat(wait.getStatus()).isEqualTo(WaitStatus.EXPIRED);
        assertThat(wait.getSettleOutcome()).isNull();
        verify(waitRepository).transitionIfStatus(wait.getWaitId(), WaitStatus.PENDING,
                WaitStatus.EXPIRED, NOW);
    }

    @Test
    void given_no_pending_when_expireRun_then_noop() {
        when(waitRepository.findByRunIdAndStatus("run-x", WaitStatus.PENDING))
                .thenReturn(List.of());

        assertThat(appService.expireRun("run-x")).isZero();
        verify(waitRepository, never()).transitionIfStatus(anyString(), any(),
                any(), any());
    }

    @Test
    void given_pending_when_expireRunReturning_then_closed_rows_reported() {
        // 票 #38：cancelRun / deny cap 终止共用收口口——收口行回报（wait-settled 帧发射用）
        AgentWait wait = raisedQuestion();
        when(waitRepository.findByRunIdAndStatus("run-1", WaitStatus.PENDING))
                .thenReturn(List.of(wait));
        when(waitRepository.transitionIfStatus(wait.getWaitId(), WaitStatus.PENDING,
                WaitStatus.EXPIRED, NOW)).thenReturn(1);

        List<WaitPointResponse> closed = appService.expireRunReturning("run-1");

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).waitId()).isEqualTo(wait.getWaitId());
        assertThat(wait.getStatus()).isEqualTo(WaitStatus.EXPIRED);
    }

    @Test
    void given_guard_missed_row_when_expireRun_then_skipped_and_not_reported() {
        // 票 #37：联动快照后行已被 settle 迁出（守卫 UPDATE 命中 0）——静默跳过、
        // 不计入收口回报（wait-settled 帧不发给已被答复的行）
        AgentWait wait = raisedQuestion();
        when(waitRepository.findByRunIdAndStatus("run-1", WaitStatus.PENDING))
                .thenReturn(List.of(wait));
        when(waitRepository.transitionIfStatus(wait.getWaitId(), WaitStatus.PENDING,
                WaitStatus.EXPIRED, NOW)).thenReturn(0);

        assertThat(appService.expireRunReturning("run-1")).isEmpty();
        assertThat(wait.getStatus()).isEqualTo(WaitStatus.PENDING); // 内存实体不动
        verify(waitRepository, never()).save(any());
    }

    @Test
    void given_session_reuse_when_cancelSessionWaits_then_pending_cancelled() {
        AgentWait wait = raisedQuestion();
        when(waitRepository.findBySessionIdAndStatus("ses_1", WaitStatus.PENDING))
                .thenReturn(List.of(wait));
        when(waitRepository.transitionIfStatus(wait.getWaitId(), WaitStatus.PENDING,
                WaitStatus.CANCELLED, NOW)).thenReturn(1);

        int cancelled = appService.cancelSessionWaits("ses_1");

        assertThat(cancelled).isEqualTo(1);
        assertThat(wait.getStatus()).isEqualTo(WaitStatus.CANCELLED);
    }

    // ---------- 替身与工具 ----------

    private AgentWait raisedQuestion() {
        return AgentWait.raise(WORKSPACE_ID, "ses_1", "run-1", WaitKind.QUESTION,
                "que_1", "用哪个框架?", Map.of("id", "que_1"), NOW);
    }

    private AgentWait raisedPermission() {
        return AgentWait.raise(WORKSPACE_ID, "ses_1", "run-1", WaitKind.PERMISSION,
                "per_1", "执行 rm -rf", Map.of("id", "per_1"), NOW);
    }

    private static final class FakeWorkspaceHandleClient implements WorkspaceHandleClient {

        @Override
        public WorkspaceHandle handleOf(String workspaceId) {
            // 按入参解析（工作区不匹配路径的判定依据）
            return WorkspaceHandle.dev(new WorkspaceId(Long.parseLong(workspaceId)),
                    "ws-1", "net-1", 4096, 0);
        }
    }

    private record ReplyRecord(String sessionId, String requestId,
                               List<List<String>> answers) {
    }

    private record PermissionRecord(String sessionId, String permissionId, boolean approve) {
    }

    /** 引擎替身：记录答复/终止派发，可注入失败。 */
    private static class RecordingAdapter implements CodingAgentAdapter {

        final List<ReplyRecord> repliedQuestions = new CopyOnWriteArrayList<>();
        final List<PermissionRecord> repliedPermissions = new CopyOnWriteArrayList<>();
        final List<String> aborts = new CopyOnWriteArrayList<>();
        volatile boolean failReplies;

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
            return new RunResult(command.runId(), "ses_1", true);
        }

        @Override
        public List<Map<String, Object>> pendingQuestions(WorkspaceHandle handle,
                                                          String sessionId) {
            return List.of();
        }

        @Override
        public void replyQuestions(WorkspaceHandle handle, String sessionId,
                                   String requestId, List<List<String>> answers) {
            if (failReplies) {
                throw new IllegalStateException("引擎不可达（测试注入）");
            }
            repliedQuestions.add(new ReplyRecord(sessionId, requestId, answers));
        }

        @Override
        public void replyPermission(WorkspaceHandle handle, String sessionId,
                                    String permissionId, boolean approve) {
            if (failReplies) {
                throw new IllegalStateException("引擎不可达（测试注入）");
            }
            repliedPermissions.add(new PermissionRecord(sessionId, permissionId, approve));
        }

        @Override
        public boolean abort(WorkspaceHandle handle, String sessionId) {
            aborts.add(sessionId);
            return true;
        }

        @Override
        public boolean health(WorkspaceHandle handle) {
            return true;
        }
    }
}
