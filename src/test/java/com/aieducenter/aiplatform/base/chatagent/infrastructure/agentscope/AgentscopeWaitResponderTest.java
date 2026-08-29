package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitQueryAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.chatagent.application.ChatAgentAppService;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope.AgentscopeChatAgentClient.ChatAgentResume;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import io.agentscope.core.event.ConfirmResult;

/**
 * {@link AgentscopeWaitResponder}（#48 等待点双向桥 settle 侧）：settle 派发 →
 * 等待点 body 恢复私货重建 ConfirmResult 与续跑入参（含 systemPrompt——挂起轮
 * agent 构建规格全量恢复）→ 异步续跑（deny cap 的 abort 关闸）。跨重启口径由
 * body 落库保证（PG 状态恢复另测 PostgresAgentStateStoreTest）。
 */
@ExtendWith(MockitoExtension.class)
class AgentscopeWaitResponderTest {

    private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");

    @Mock
    private AgentWaitQueryAppService waitQueryService;

    @Mock
    private AgentscopeChatAgentClient client;

    @Mock
    private ChatAgentAppService appService;

    @Mock
    private Consumer<AgentEvent> sinkMock;

    /** 直通执行器（测试同步验证 resume 提交）。 */
    private final Executor directExecutor = Runnable::run;

    private AgentscopeWaitResponder responder;

    @BeforeEach
    void setUp() {
        responder = new AgentscopeWaitResponder(waitQueryService, client, appService,
                directExecutor);
    }

    @Test
    void given_permission_wait_when_reply_approve_then_resumed_with_confirmed_results() {
        givenPendingWait("reply-9", WaitKind.PERMISSION, Map.of(
                "toolCalls", List.of(Map.of(
                        "id", "tc-1", "name", "write_file",
                        "input", Map.of("path", "docs/PRD.md"))),
                "modelString", "deepseek:deepseek-v4-flash",
                "systemPrompt", "你是 BA 访谈智能体。",
                "userId", "alice",
                "usageContext", Map.of("subject", "prj-42", "dims", Map.of("projectId", "42")),
                "streamCorrelation", Map.of("projectId", "42")));
        when(appService.sink(any(), any())).thenReturn(sinkMock);

        responder.replyPermission(handle(), "s-1", "reply-9", true);

        ArgumentCaptor<ChatAgentResume> captor = ArgumentCaptor.forClass(ChatAgentResume.class);
        verify(client).resume(captor.capture(), any());
        ChatAgentResume resume = captor.getValue();
        assertThat(resume.runId()).isEqualTo("run-1");
        assertThat(resume.sessionId()).isEqualTo("s-1");
        assertThat(resume.userId()).isEqualTo("alice");
        assertThat(resume.workspaceId()).isEqualTo("42");
        assertThat(resume.modelString()).isEqualTo("deepseek:deepseek-v4-flash");
        // 挂起轮的 agent 构建规格全量恢复（含命令级 systemPrompt，不静默换默认人格）
        assertThat(resume.systemPrompt()).isEqualTo("你是 BA 访谈智能体。");
        assertThat(resume.replyId()).isEqualTo("reply-9");
        assertThat(resume.resumeText()).isEqualTo("approved");
        assertThat(resume.usageContext().subject()).isEqualTo("prj-42");
        assertThat(resume.streamCorrelation()).containsEntry("projectId", "42");
        // 批复：每个待确认工具一个 ConfirmResult，approve = confirmed
        assertThat(resume.confirmResults()).hasSize(1);
        ConfirmResult result = resume.confirmResults().get(0);
        assertThat(result.isConfirmed()).isTrue();
        assertThat(result.getToolCall().getId()).isEqualTo("tc-1");
        assertThat(result.getToolCall().getName()).isEqualTo("write_file");
        assertThat(result.getToolCall().getInput()).containsEntry("path", "docs/PRD.md");
        // #51：重建块的 content 必须回填 input 的 JSON 串——重放校验
        // （ToolValidator.validateInput）只认 content 原文，null 会炸
        // 「argument "content" is null」参数校验错误结果给模型（见错重问/自述系统错误）
        assertThat(result.getToolCall().getContent())
                .contains("\"path\":\"docs/PRD.md\"");
        // 续跑流的 sink 走 AppService 流桥（workspaceId + 关联字段——再挂起/终态同口径）
        verify(appService).sink("42", Map.of("projectId", "42"));
    }

    @Test
    void given_permission_wait_when_reply_deny_then_denied_results() {
        givenPendingWait("reply-9", WaitKind.PERMISSION, Map.of(
                "toolCalls", List.of(Map.of(
                        "id", "tc-1", "name", "write_file", "input", Map.of()))));
        when(appService.sink(any(), any())).thenReturn(sinkMock);

        responder.replyPermission(handle(), "s-1", "reply-9", false);

        ArgumentCaptor<ChatAgentResume> captor = ArgumentCaptor.forClass(ChatAgentResume.class);
        verify(client).resume(captor.capture(), any());
        assertThat(captor.getValue().resumeText()).isEqualTo("denied");
        assertThat(captor.getValue().confirmResults().get(0).isConfirmed()).isFalse();
    }

    @Test
    void given_question_wait_when_reply_answer_then_answer_injected_into_tool_input() {
        // 向用户提问（QUESTION 载荷形状）：答复文本注入工具 input（ask_user 的
        // answer）+ 进恢复消息文本（LLM 上下文直接可读）
        givenPendingWait("reply-10", WaitKind.QUESTION, Map.of(
                "toolCalls", List.of(Map.of(
                        "id", "tc-2", "name", "ask_user",
                        "input", Map.of("question", "用哪个框架?", "options",
                                List.of("Spring Boot", "Quarkus")))),
                "userId", "", "modelString", ""));
        when(appService.sink(any(), any())).thenReturn(sinkMock);

        responder.replyQuestions(handle(), "s-1", "reply-10",
                List.of(List.of("Spring Boot")));

        ArgumentCaptor<ChatAgentResume> captor = ArgumentCaptor.forClass(ChatAgentResume.class);
        verify(client).resume(captor.capture(), any());
        ChatAgentResume resume = captor.getValue();
        assertThat(resume.resumeText()).isEqualTo("Spring Boot");
        assertThat(resume.userId()).isNull();
        assertThat(resume.modelString()).isNull();
        ConfirmResult result = resume.confirmResults().get(0);
        assertThat(result.isConfirmed()).isTrue();
        assertThat(result.getToolCall().getInput())
                .containsEntry("question", "用哪个框架?")
                .containsEntry("answer", "Spring Boot");
        // #51：content 同步回填（含注入的 answer）——答复重放执行前先过参数校验
        assertThat(result.getToolCall().getContent())
                .contains("\"answer\":\"Spring Boot\"");
    }

    @Test
    void given_engine_name_when_queried_then_agentscope() {
        assertThat(responder.engine()).isEqualTo("agentscope");
    }

    @Test
    void given_aborted_session_when_reply_then_resume_not_submitted() {
        // deny cap 终止后（abort 已先达）：不再提交续跑
        givenPendingWait("reply-11", WaitKind.PERMISSION, Map.of(
                "toolCalls", List.of(Map.of(
                        "id", "tc-1", "name", "write_file", "input", Map.of()))));

        assertThat(responder.abort(handle(), "s-1")).isTrue();
        responder.replyPermission(handle(), "s-1", "reply-11", false);

        verify(client, never()).resume(any(), any());
    }

    // ---------- 内部 ----------

    private void givenPendingWait(String engineRef, WaitKind kind, Map<String, Object> data) {
        when(waitQueryService.pendingByRef("s-1", engineRef))
                .thenReturn(Optional.of(waitPoint(kind, data)));
    }

    private static WaitPointResponse waitPoint(WaitKind kind, Map<String, Object> data) {
        return new WaitPointResponse("wait-1", "42", "s-1", "run-1", "reply-9", kind, null,
                WaitStatus.PENDING, null, null, data, null, null, NOW, null);
    }

    private static WorkspaceHandle handle() {
        return WorkspaceHandle.dev(WorkspaceId.of("42"), "ws-42-dev", "net-42", 0, 0);
    }
}
