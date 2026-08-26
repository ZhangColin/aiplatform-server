package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitQueryAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.chatagent.application.ChatAgentAppService;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectWaitSettleCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectAgentTaskResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * BA 访谈编排（#40 访谈循环本体）：BA 换载体——对话命令的会话寻址（projectId →
 * ba-{projectId} 稳定绑定，自由补充/续跑都续此会话）、计量归属（role=BA 维度）、
 * role-assigned 帧序（engine=agentscope）、阶段计数（G1 计数门在 #49 换谓词前不塌）。
 */
@SpringBootTest
class BaInterviewAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private BaInterviewAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private IterationRepository iterationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ChatAgentAppService chatAgentAppService;

    @MockitoBean
    private AgentStreamAppService streamAppService;

    @MockitoBean
    private AgentWaitQueryAppService waitQueryService;

    /** 化解路由的 settle 走项目等待点通道（SSE/QA 摄取同答卡 REST 口径）。 */
    @MockitoBean
    private ProjectWaitAppService projectWaitAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_iterations");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_project_when_interview_turn_then_command_bound_to_ba_session() {
        Long projectId = persistedProjectWithIteration("9700");
        when(chatAgentAppService.converseAsync(any(), any())).thenReturn(true);

        ProjectAgentTaskResponse response = appService.runInterviewTurn(projectId, "做一个官网");

        // 对话命令全要素：BA 会话稳定绑定 + owner 寻址 + 角色卡模型/人格 + 计量归属 + 项目工作区 + 流关联
        ArgumentCaptor<ChatAgentCommand> command = ArgumentCaptor.forClass(ChatAgentCommand.class);
        verify(chatAgentAppService).converseAsync(command.capture(), any());
        ChatAgentCommand value = command.getValue();
        assertThat(value.runId()).isEqualTo(response.runId());
        assertThat(value.prompt()).isEqualTo("做一个官网");
        assertThat(value.sessionId()).isEqualTo("ba-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).contains("ask_user");
        assertThat(value.modelString()).isEqualTo("deepseek:deepseek-v4-flash");
        assertThat(value.workspaceId()).isEqualTo("9700");
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
        assertThat(value.usageContext().subject()).isEqualTo(projectId.toString());
        assertThat(value.usageContext().dims())
                .containsEntry("role", "BA")
                .containsEntry("stage", ProjectMainChain.STAGE_BA)
                .containsKey("iterationId");

        // 响应契约同引擎任务面（engine=agentscope 标双轨）
        assertThat(response.sessionId()).isEqualTo("ba-" + projectId);
        assertThat(response.engine()).isEqualTo("agentscope");
        assertThat(response.role()).isEqualTo(RolePreset.BA);
        assertThat(response.roleName()).isEqualTo("需求分析师");
        assertThat(response.stage()).isEqualTo(ProjectMainChain.STAGE_BA);
        assertThat(response.accepted()).isTrue();
    }

    @Test
    void given_open_iteration_when_turn_then_role_assigned_first_and_stage_counted() {
        Long projectId = persistedProjectWithIteration("9701");
        when(chatAgentAppService.converseAsync(any(), any())).thenReturn(true);

        ProjectAgentTaskResponse response = appService.runInterviewTurn(projectId, "梳理需求");

        // 帧序 role-assigned（engine=agentscope）→ task-start（converseAsync 内）：
        // 与引擎路径同契约（SSE事件清单）
        InOrder order = inOrder(streamAppService, chatAgentAppService);
        order.verify(streamAppService).publish(org.mockito.ArgumentMatchers.eq(
                        AgentEventTypes.ROLE_ASSIGNED),
                org.mockito.ArgumentMatchers.anyMap());
        order.verify(chatAgentAppService).converseAsync(any(), any());

        // 阶段计数：G1 计数门（taskCount≥1）在 #49 换「PRD 已产出」谓词前不塌
        Iteration iteration = iterationRepository
                .findByProjectIdAndStatus(projectId,
                        com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus.OPEN)
                .orElseThrow();
        assertThat(iteration.getStageTaskCount()).isEqualTo(1);
        assertThat(response.accepted()).isTrue();
    }

    @Test
    void given_submission_rejected_when_turn_then_not_counted_and_not_accepted() {
        Long projectId = persistedProjectWithIteration("9702");
        when(chatAgentAppService.converseAsync(any(), any())).thenReturn(false);

        ProjectAgentTaskResponse response = appService.runInterviewTurn(projectId, "梳理需求");

        // 提交被拒（复活后与关闸竞态的极端窗口）不计数、accepted=false——与引擎
        // 「被拒的 run 没有创建事实」同口径
        assertThat(response.accepted()).isFalse();
        Iteration iteration = iterationRepository
                .findByProjectIdAndStatus(projectId,
                        com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus.OPEN)
                .orElseThrow();
        assertThat(iteration.getStageTaskCount()).isZero();
    }

    @Test
    void given_no_open_iteration_when_turn_then_stage_closed_dims_without_iteration() {
        // 期收口后（期后场景）照常可谈：stage=CLOSED、dims 归项目不归期（与引擎口径同构）
        Project project = projectRepository.save(Project.create("无期项目", null, "opencode",
                9703L, OWNER));

        ProjectAgentTaskResponse response = appService.runInterviewTurn(project.getId(), "再聊聊");

        assertThat(response.stage()).isEqualTo(ProjectMainChain.STAGE_CLOSED);
        ArgumentCaptor<ChatAgentCommand> command = ArgumentCaptor.forClass(ChatAgentCommand.class);
        verify(chatAgentAppService).converseAsync(command.capture(), any());
        assertThat(command.getValue().usageContext().dims())
                .containsEntry("role", "BA")
                .containsEntry("stage", ProjectMainChain.STAGE_CLOSED)
                .doesNotContainKey("iterationId");
        // role-assigned 照发（stage 快照 CLOSED）——与引擎路径帧序契约一致
        verify(streamAppService).publish(org.mockito.ArgumentMatchers.eq(
                        AgentEventTypes.ROLE_ASSIGNED),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void given_pending_question_when_turn_then_settled_with_prompt_instead_of_new_turn() {
        // 在悬化解路由：会话有在悬问答时，自由补充/催促文本＝对该问题的口头回答——
        // settle 续跑（锚回原 run）而非开新轮（AgentScope 会话内有 ASKING 工具时
        // 新消息直接报错），且不重复计数、不发 role-assigned（run 开场已发）
        Long projectId = persistedProjectWithIteration("9704");
        when(waitQueryService.pendingOfSession("ba-" + projectId)).thenReturn(List.of(
                waitPoint("wait-q1", "run-in-flight",
                        WaitKind.QUESTION)));

        ProjectAgentTaskResponse response = appService.runInterviewTurn(projectId,
                "别问了，直接出访谈总结");

        verify(projectWaitAppService).settle(projectId, "wait-q1",
                new ProjectWaitSettleCommand("answer",
                        List.of(List.of("别问了，直接出访谈总结")), null, null, null));
        verify(chatAgentAppService, never()).converseAsync(any(), any());
        assertThat(response.runId()).isEqualTo("run-in-flight");
        assertThat(response.accepted()).isTrue();
        assertThat(openIterationOf(projectId).getStageTaskCount()).isZero();
        verify(streamAppService, never()).publish(any(), any());
    }

    @Test
    void given_pending_permission_only_when_turn_then_new_turn_as_usual() {
        // 权限类在悬不在此化解（卡片批复是唯一通道）——照常开新轮
        Long projectId = persistedProjectWithIteration("9705");
        when(waitQueryService.pendingOfSession("ba-" + projectId)).thenReturn(List.of(
                waitPoint("wait-p1", "run-in-flight",
                        WaitKind.PERMISSION)));
        when(chatAgentAppService.converseAsync(any(), any())).thenReturn(true);

        ProjectAgentTaskResponse response = appService.runInterviewTurn(projectId, "继续聊");

        verify(projectWaitAppService, never()).settle(any(), any(), any());
        verify(chatAgentAppService).converseAsync(any(), any());
        assertThat(response.runId()).isNotBlank();
    }

    @Test
    void given_missing_project_when_turn_then_prj_001() {
        assertThatThrownBy(() -> appService.runInterviewTurn(-1L, "梳理需求"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- 测试数据 ----------

    private Iteration openIterationOf(Long projectId) {
        return iterationRepository.findByProjectIdAndStatus(projectId,
                com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus.OPEN)
                .orElseThrow();
    }

    private static WaitPointResponse waitPoint(String waitId, String runId, WaitKind kind) {
        return new WaitPointResponse(waitId, "9704", "ba-session", runId, "reply-1",
                kind, null, WaitStatus.PENDING, null, "summary", null, null, null,
                java.time.Instant.now(), null);
    }

    private Long persistedProjectWithIteration(String workspaceId) {
        Project project = projectRepository.save(Project.create("访谈项目", null, "opencode",
                Long.parseLong(workspaceId), OWNER));
        iterationRepository.save(Iteration.open(project.getId(), 1,
                ProjectMainChain.firstStage()));
        return project.getId();
    }
}
