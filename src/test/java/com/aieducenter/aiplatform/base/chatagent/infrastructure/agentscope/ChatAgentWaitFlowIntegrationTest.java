package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitQueryAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentSessionRepository;
import com.aieducenter.aiplatform.base.chatagent.application.ChatAgentAppService;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentSessionRecorder;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentWorkspaceClient;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope.AgentscopeChatAgentClient.ChatAgentResume;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

/**
 * #48 等待点双向桥集成（真 PG 落库 + 组装链）：挂起帧 → 等待点行（body 带恢复
 * 私货）+ SSE 补发补 waitId；会话行登记跨「重启」（行判定）不重发；settle(answer)
 * → 等待点答复通道重建 ConfirmResult 续跑（新实例=重启后仍可续，访谈上下文在
 * AgentState 槽位）；deny cap 三次拒绝目录寻址 agentscope 通道不炸（既有守卫
 * 同口径生效）。
 */
@SpringBootTest
class ChatAgentWaitFlowIntegrationTest {

    private static final String SESSION = "ses-it-48";
    private static final long WORKSPACE_ID = 42L;

    @Autowired
    private com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService
            waitAppService;

    @Autowired
    private AgentWaitQueryAppService waitQueryService;

    @Autowired
    private ChatAgentAppService appService;

    @Autowired
    private ChatAgentSessionRecorder sessionRecorder;

    @Autowired
    private AgentSessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ChatAgentWorkspaceClient workspaceClient;

    @MockBean
    private com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient
            engineWorkspaceHandleClient;

    @MockBean
    private AgentStreamAppService streamAppService;

    @MockBean
    private AgentscopeChatAgentClient chatAgentClient;

    private AgentscopeWaitResponder responder;

    @BeforeEach
    void setUp() {
        WorkspaceHandle handle = WorkspaceHandle.dev(
                WorkspaceId.of("42"), "ws-42-dev", "net-42", 0, 0);
        when(workspaceClient.handleOf("42")).thenReturn(handle);
        when(engineWorkspaceHandleClient.handleOf("42")).thenReturn(handle);
        // 直通执行器：settle 同步完成续跑提交（resume 内容可立即断言）
        Executor direct = Runnable::run;
        responder = new AgentscopeWaitResponder(waitQueryService, chatAgentClient,
                appService, direct);
        jdbcTemplate.update("DELETE FROM agt_pending_waits WHERE session_id = ?", SESSION);
        jdbcTemplate.update("DELETE FROM agt_agent_sessions WHERE session_id = ?", SESSION);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM agt_pending_waits WHERE session_id = ?", SESSION);
        jdbcTemplate.update("DELETE FROM agt_agent_sessions WHERE session_id = ?", SESSION);
    }

    @Test
    void given_suspension_frame_when_sunk_then_wait_row_registered_and_sse_supplemented() {
        Consumer<AgentEvent> sink = appService.sink("42", Map.of("projectId", "42"));

        sink.accept(waitRaisedFrame("run-1", "reply-1", "ask_user", Map.of(
                "question", "用哪个框架?", "options", List.of("Spring Boot"))));

        // 等待点行（QUESTION 载荷形状，body=引擎载荷原样含恢复私货）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT wait_id, kind, status, engine_ref FROM agt_pending_waits"
                        + " WHERE session_id = ? AND engine_ref = ?", SESSION, "reply-1");
        assertThat(row.get("kind")).isEqualTo(WaitKind.QUESTION.getCode());
        assertThat(row.get("status")).isEqualTo(1);
        // SSE 补发带 waitId（挂起可见）
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(streamAppService).publish(any(), payload.capture());
        assertThat(payload.getValue()).containsKey(AgentEventTypes.WAIT_ID_FIELD);
    }

    @Test
    void given_session_row_when_recorded_twice_then_first_seen_only_once() {
        boolean first = sessionRecorder.recordIfAbsent("42", SESSION, "run-1");
        boolean second = sessionRecorder.recordIfAbsent("42", SESSION, "run-2");

        // 跨重启口径：行判定（首见一次）；settle 可续跑前置（行在库）
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(sessionRepository.findBySessionId(SESSION)).isPresent();
    }

    @Test
    void given_suspended_wait_when_settle_answer_then_resumed_with_rebuilt_confirm() {
        // 「重启」后 settle：答复通道以新实例从等待点 body 重建续跑（跨进程恢复口径）
        appService.sink("42", Map.of("projectId", "42"))
                .accept(waitRaisedFrame("run-9", "reply-9", "ask_user", Map.of(
                        "question", "用哪个框架?", "options", List.of("Spring Boot"))));
        sessionRecorder.recordIfAbsent("42", SESSION, "run-9");

        responder.replyQuestions(null, SESSION, "reply-9", List.of(List.of("Spring Boot")));

        ArgumentCaptor<ChatAgentResume> captor = ArgumentCaptor.forClass(ChatAgentResume.class);
        verify(chatAgentClient).resume(captor.capture(), any());
        ChatAgentResume resume = captor.getValue();
        assertThat(resume.runId()).isEqualTo("run-9");
        assertThat(resume.resumeText()).isEqualTo("Spring Boot");
        assertThat(resume.confirmResults()).hasSize(1);
        assertThat(resume.confirmResults().get(0).getToolCall().getName())
                .isEqualTo("ask_user");
        assertThat(resume.confirmResults().get(0).getToolCall().getInput())
                .containsEntry("answer", "Spring Boot");
    }

    @Test
    void given_denies_reach_cap_when_settle_via_directory_then_existing_guards_apply() {
        // directory 寻址 agentscope 通道（非编码引擎注册表）：既有守卫（deny cap、
        // 会话可续跑校验、先引擎后落库）对对话智能体同口径生效
        sessionRecorder.recordIfAbsent("42", SESSION, "run-cap");
        for (int i = 1; i <= 3; i++) {
            appService.sink("42", null).accept(waitRaisedFrame("run-cap", "ref-" + i,
                    "write_file", Map.of("path", "x")));
        }

        for (int i = 1; i <= 3; i++) {
            waitAppService.settle("42", waitIdOf("ref-" + i),
                    new WaitSettleCommand("permission", null, Boolean.FALSE, null));
        }

        // deny cap 触发：本 run 剩余等待点被收口（既有 terminateIfDenyCapped 语义）
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agt_pending_waits WHERE session_id = ? AND status = 1",
                Integer.class, SESSION);
        assertThat(pending).isZero();
        Integer denies = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agt_pending_waits WHERE session_id = ?"
                        + " AND settle_outcome = 3",
                Integer.class, SESSION);
        assertThat(denies).isEqualTo(3);
    }

    // ---------- 内部 ----------

    private String waitIdOf(String engineRef) {
        return jdbcTemplate.queryForObject(
                "SELECT wait_id FROM agt_pending_waits WHERE session_id = ? AND engine_ref = ?",
                String.class, SESSION, engineRef);
    }

    /** 挂起帧（client 的 waitRaised 产物形状，单测已覆盖生成——此处直喂流桥）。 */
    private static AgentEvent waitRaisedFrame(String runId, String replyId, String toolName,
                                               Map<String, Object> input) {
        Map<String, Object> data = Map.of(
                "type", AskUserTool.NAME.equals(toolName) ? "question" : "permission",
                "toolCalls", List.of(Map.of(
                        "id", "tc-" + replyId, "name", toolName, "input", input)),
                "modelString", "deepseek:deepseek-v4-flash",
                "userId", "alice",
                "usageContext", Map.of("subject", "prj-42", "dims", Map.of()),
                "streamCorrelation", Map.of());
        return new AgentEvent(AgentEventTypes.WAIT_RAISED, Map.of(
                AgentEventTypes.WAIT_RUN_FIELD, runId,
                AgentEventTypes.WAIT_SESSION_FIELD, SESSION,
                "engine", "agentscope",
                AgentEventTypes.WAIT_KIND_FIELD, "ask_user".equals(toolName)
                        ? "QUESTION" : "PERMISSION",
                AgentEventTypes.WAIT_SUMMARY_FIELD, toolName,
                AgentEventTypes.WAIT_ENGINE_REF_FIELD, replyId,
                AgentEventTypes.WAIT_DATA_FIELD, data));
    }
}
