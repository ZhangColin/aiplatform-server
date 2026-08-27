package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext;
import com.aieducenter.aiplatform.base.agentengine.application.AgentSessionAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentTaskAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentTaskDispatchCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentSessionResponse;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentTaskResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectAgentTaskCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectAgentTaskResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

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
 * 项目智能体任务编排（片5a 验收第 2 步的编排面）：角色解析（阶段默认/显式）、
 * role-assigned 先发、UsageContext 组装（subject=projectId + dims role/stage）、
 * projectId 流关联注入、阶段计数（接受即计/拒绝不计/收口不计）。
 */
@SpringBootTest
class ProjectAgentTaskAppServiceTest {

    @Autowired
    private ProjectAgentTaskAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private IterationRepository iterationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 底座编排入口 mock：真实桥接行为在 AgentTaskAppServiceTest 覆盖。 */
    @MockitoBean
    private AgentTaskAppService agentTaskAppService;

    @MockitoBean
    private AgentStreamAppService streamAppService;

    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    /** 知识端口 mock（A5 §3 注入单点缝验证；真实检索链路见 KnowledgeAppServiceTest）。 */
    @MockitoBean
    private KnowledgePort knowledgePort;

    /** BA 访谈编排 mock（#40 双轨分野：BA 角色路由目标；真实编排见 BaInterviewAppServiceTest）。 */
    @MockitoBean
    private BaInterviewAppService baInterviewAppService;

    /** 会话查询 mock（#46 Demo 会话寻址——修正 run 续会话的解析口）。 */
    @MockitoBean
    private AgentSessionAppService agentSessionAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_iterations");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_open_ba_iteration_when_dispatch_without_role_then_routed_to_interview() {
        // #40 双轨分野：BA 段自由补充（阶段默认角色 BA）续 BA 会话——引擎零交互、
        // 不做知识注入（访谈是对话上下文不是 run prompt）
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_BA);
        when(baInterviewAppService.runInterviewTurn(project.getId(), "补充个信息"))
                .thenReturn(new ProjectAgentTaskResponse("run-1", "ba-" + project.getId(),
                        "agentscope", RolePreset.BA, "需求分析师",
                        ProjectMainChain.STAGE_BA, true));

        ProjectAgentTaskResponse response = appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("补充个信息", null));

        assertThat(response.role()).isEqualTo(RolePreset.BA);
        assertThat(response.runId()).isEqualTo("run-1");
        assertThat(response.engine()).isEqualTo("agentscope");
        assertThat(response.accepted()).isTrue();
        verify(baInterviewAppService).runInterviewTurn(project.getId(), "补充个信息");
        verifyNoInteractions(agentTaskAppService, knowledgePort, streamAppService);
    }

    @Test
    void given_explicit_ba_role_when_dispatch_then_routed_to_interview_regardless_of_stage() {
        // 显式 BA（任意阶段，如催促收敛「直接出 PRD」）同走对话轨道续会话
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_DEV);

        appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("别问了，直接出 PRD", RolePreset.BA));

        verify(baInterviewAppService).runInterviewTurn(project.getId(), "别问了，直接出 PRD");
        verifyNoInteractions(agentTaskAppService);
    }

    @Test
    void given_explicit_dev_role_when_dispatch_then_preset_used_over_default() {
        Project project = persistedProject("dsh");
        persistedIteration(project, ProjectMainChain.STAGE_BA);
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-2", "dsh-1", "dsh", true));

        ProjectAgentTaskResponse response = appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("按 PRD 开发", RolePreset.DEV));

        assertThat(response.role()).isEqualTo(RolePreset.DEV);
        assertThat(response.roleName()).isEqualTo("开发工程师");
        ArgumentCaptor<AgentTaskDispatchCommand> command =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        ArgumentCaptor<AgentRunContext> runContext = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(agentTaskAppService).dispatch(anyString(), command.capture(), runContext.capture());
        assertThat(command.getValue().systemPrompt()).isEqualTo(RolePreset.DEV.systemPrompt());
        assertThat(command.getValue().modelId()).isEqualTo(RolePreset.DEV.modelId());
        assertThat(command.getValue().engine()).isEqualTo("dsh");
        assertThat(runContext.getValue().usageContext().subject())
                .isEqualTo(project.getId().toString());
        // 计量维度（A6 §3）：role + stage + iterationId（OPEN 期 run 发起时快照）
        assertThat(runContext.getValue().usageContext().dims())
                .containsEntry("role", "DEV").containsEntry("stage", ProjectMainChain.STAGE_BA)
                .containsEntry("iterationId", openIteration(project).getId().toString());

        // role-assigned 先于 run 下发发射（帧序 role-assigned → task-start → …；引擎路径）
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(streamAppService).publish(eq(AgentEventTypes.ROLE_ASSIGNED), payload.capture());
        assertThat(payload.getValue()).containsEntry("projectId", project.getId().toString())
                .containsEntry("runId", runContext.getValue().runId())
                .containsEntry("role", "DEV")
                .containsEntry("stage", ProjectMainChain.STAGE_BA)
                .containsEntry("engine", "dsh");
        assertThat(openIteration(project).getStageTaskCount()).isEqualTo(1);
    }

    @Test
    void given_stage_without_default_role_when_dispatch_without_role_then_prj_004() {
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_TEST); // 测试段无默认角色（A3 §2.2）

        assertThatThrownBy(() -> appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("测一下", null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.ROLE_REQUIRED.message());
        verifyNoInteractions(agentTaskAppService, streamAppService);
    }

    @Test
    void given_no_open_iteration_when_dispatch_without_role_then_prj_004() {
        Project project = persistedProject("opencode"); // 无 OPEN 期 = 已交付，无阶段默认角色

        assertThatThrownBy(() -> appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("修个 bug", null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.ROLE_REQUIRED.message());
    }

    @Test
    void given_no_open_iteration_when_dispatch_explicit_role_then_runs_uncounted() {
        Project project = persistedProject("opencode");
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-3", "ses-3", "opencode", true));

        // 期后修复（工具与过程正交）：显式角色任务照常跑，不进过程计数
        ProjectAgentTaskResponse response = appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("修个 bug", RolePreset.DEV));

        assertThat(response.role()).isEqualTo(RolePreset.DEV);
        assertThat(response.stage()).isEqualTo(ProjectMainChain.STAGE_CLOSED);
        assertThat(iterationRepository.findByProjectIdAndStatus(project.getId(),
                IterationStatus.OPEN)).isEmpty();
    }

    @Test
    void given_rejected_run_when_dispatch_then_not_counted() {
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_DEV);
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-4", null, "opencode", false));

        ProjectAgentTaskResponse response = appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("开发登录页", RolePreset.DEV));

        assertThat(response.accepted()).isFalse();
        assertThat(openIteration(project).getStageTaskCount()).isZero();
    }

    @Test
    void given_dev_stage_first_test_task_when_dispatch_then_advance_to_test() {
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_DEV);
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-t1", "ses-t1", "opencode", true));

        ProjectAgentTaskResponse response = appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("测试一下首页", RolePreset.TEST));

        // 开发→测试唯一触发：首个测试任务被接受即 advance（A3 §2.3），计数落测试段
        Iteration iteration = openIteration(project);
        assertThat(iteration.getStage()).isEqualTo(ProjectMainChain.STAGE_TEST);
        assertThat(iteration.getStageTaskCount()).isEqualTo(1);
        assertThat(response.role()).isEqualTo(RolePreset.TEST);

        // SSE stage-changed（编排触发：无 approved/rejected 标记）
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationAppService).publish(eq(ProjectEventTypes.STAGE_CHANGED),
                payload.capture());
        assertThat(payload.getValue())
                .containsEntry("projectId", project.getId().toString())
                .containsEntry("stage", ProjectMainChain.STAGE_TEST)
                .containsEntry("stageLabel", "测试")
                .doesNotContainKey("approved")
                .doesNotContainKey("rejected");
    }

    @Test
    void given_test_stage_when_dispatch_test_task_then_no_advance() {
        // 复测场景（A4 §5）：已在测试段，测试任务不重复推进
        Project project = persistedProject("opencode");
        Iteration iteration = persistedIteration(project, ProjectMainChain.STAGE_TEST);
        iteration.recordStageTask();
        iterationRepository.save(iteration);
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-t2", "ses-t2", "opencode", true));

        appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("复测首页", RolePreset.TEST));

        assertThat(openIteration(project).getStage()).isEqualTo(ProjectMainChain.STAGE_TEST);
        assertThat(openIteration(project).getStageTaskCount()).isEqualTo(2);
        verify(notificationAppService, never()).publish(anyString(), any());
    }

    @Test
    void given_dev_stage_dev_task_when_dispatch_then_no_advance() {
        // 开发段上的开发/其他角色任务不触发推进——只有测试任务是触发器
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_DEV);
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-d1", "ses-d1", "opencode", true));

        appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("继续开发", RolePreset.DEV));

        assertThat(openIteration(project).getStage()).isEqualTo(ProjectMainChain.STAGE_DEV);
        assertThat(openIteration(project).getStageTaskCount()).isEqualTo(1);
        verify(notificationAppService, never()).publish(anyString(), any());
    }

    @Test
    void given_rejected_test_task_when_dispatch_then_no_advance() {
        // 引擎拒绝 = 没有创建事实：不推进不计数
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_DEV);
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-t3", null, "opencode", false));

        appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("测试一下", RolePreset.TEST));

        assertThat(openIteration(project).getStage()).isEqualTo(ProjectMainChain.STAGE_DEV);
        assertThat(openIteration(project).getStageTaskCount()).isZero();
        verify(notificationAppService, never()).publish(anyString(), any());
    }

    // ---------- #46 G2 驳回回流：Demo 修正 run 续 Demo 会话（sessionId 复用） ----------

    @Test
    void given_workspace_engine_session_when_dispatch_demo_correction_then_continue_demo_session() {
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_DEMO);
        when(agentSessionAppService.listByWorkspace(Long.toString(project.getWorkspaceId())))
                .thenReturn(List.of(sessionOf("ses-demo", "opencode")));
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-c1", "ses-demo", "opencode", true));

        ProjectAgentTaskResponse response = appService.dispatchDemoCorrectionRun(project.getId(),
                "按驳回意见修正首页");

        // 续 Demo 会话而非新会话：sessionId 复用（DEMO 段引擎会话的常态唯一来源——
        // 自动 Demo run 与历次修正 run）
        ArgumentCaptor<AgentTaskDispatchCommand> command =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        verify(agentTaskAppService).dispatch(anyString(), command.capture(), any());
        assertThat(command.getValue().sessionId()).isEqualTo("ses-demo");
        assertThat(command.getValue().systemPrompt()).isEqualTo(RolePreset.DEMO.systemPrompt());
        assertThat(command.getValue().engine()).isEqualTo("opencode");
        assertThat(response.role()).isEqualTo(RolePreset.DEMO);
        assertThat(response.sessionId()).isEqualTo("ses-demo");
        // 阶段计数照记（G2 门重新就绪的计数输入，#46 往复至通过）
        assertThat(openIteration(project).getStageTaskCount()).isEqualTo(1);
    }

    @Test
    void given_engine_mismatch_session_when_dispatch_demo_correction_then_fresh_session() {
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_DEMO);
        // 他引擎会话在前（残留/手起）——引擎不符不续，新起会话兜底
        when(agentSessionAppService.listByWorkspace(Long.toString(project.getWorkspaceId())))
                .thenReturn(List.of(sessionOf("ses-dsh", "dsh")));
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-c2", "ses-new", "opencode", true));

        appService.dispatchDemoCorrectionRun(project.getId(), "按驳回意见修正首页");

        ArgumentCaptor<AgentTaskDispatchCommand> command =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        verify(agentTaskAppService).dispatch(anyString(), command.capture(), any());
        assertThat(command.getValue().sessionId()).isNull();
        assertThat(openIteration(project).getStageTaskCount()).isEqualTo(1);
    }

    @Test
    void given_no_session_when_dispatch_demo_correction_then_fresh_session() {
        // 无任何会话（异常路径：Demo run 未登记/已清理）同新起——回流不因缺会话失败
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_DEMO);
        when(agentSessionAppService.listByWorkspace(Long.toString(project.getWorkspaceId())))
                .thenReturn(List.of());
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-c3", "ses-new", "opencode", true));

        ProjectAgentTaskResponse response = appService.dispatchDemoCorrectionRun(project.getId(),
                "按驳回意见修正首页");

        ArgumentCaptor<AgentTaskDispatchCommand> command =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        verify(agentTaskAppService).dispatch(anyString(), command.capture(), any());
        assertThat(command.getValue().sessionId()).isNull();
        assertThat(response.accepted()).isTrue();
    }

    // ---------- A4 §5 期联动：人测试任务的 advance 守卫（票 #26） ----------

    @Test
    void given_dev_stage_when_human_test_task_created_then_advance_and_stage_changed() {
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_DEV);

        boolean advanced = appService.advanceToTestOnTestTaskCreation(project.getId());

        assertThat(advanced).isTrue();
        assertThat(openIteration(project).getStage()).isEqualTo(ProjectMainChain.STAGE_TEST);
        assertThat(openIteration(project).getStageTaskCount()).isZero(); // 人任务不计数
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationAppService).publish(eq(ProjectEventTypes.STAGE_CHANGED),
                payload.capture());
        assertThat(payload.getValue())
                .containsEntry("stage", ProjectMainChain.STAGE_TEST)
                .containsEntry("stageLabel", "测试")
                .doesNotContainKey("approved")
                .doesNotContainKey("rejected"); // 编排触发，非门决策
    }

    @Test
    void given_test_stage_when_human_test_task_created_then_noop() {
        // 复测场景：已在测试段不动
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_TEST);

        assertThat(appService.advanceToTestOnTestTaskCreation(project.getId())).isFalse();
        assertThat(openIteration(project).getStage()).isEqualTo(ProjectMainChain.STAGE_TEST);
        verify(notificationAppService, never()).publish(anyString(), any());
    }

    @Test
    void given_closed_or_missing_project_when_human_test_task_created_then_noop_or_prj_001() {
        // 期 CLOSED（期后修复）：不动
        Project closed = persistedProject("opencode");
        Iteration iteration = Iteration.open(closed.getId(), Iteration.FIRST_SEQ,
                ProjectMainChain.STAGE_ACCEPTANCE);
        iteration.close(ProjectMainChain.STAGE_CLOSED);
        iterationRepository.save(iteration);

        assertThat(appService.advanceToTestOnTestTaskCreation(closed.getId())).isFalse();
        verify(notificationAppService, never()).publish(anyString(), any());

        // 项目不存在：PRJ_001（task BC 建任务的前置把关）
        assertThatThrownBy(() -> appService.advanceToTestOnTestTaskCreation(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_missing_project_when_dispatch_then_prj_001() {
        assertThatThrownBy(() -> appService.dispatchTask(-1L,
                new ProjectAgentTaskCommand("干活", null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- A5 §3 检索注入单点缝（dispatchTask / dispatchFixRun 共用） ----------

    @Test
    void given_knowledge_hits_when_dispatch_then_prompt_injected_and_sse_emitted() {
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_BA);
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-k1", "ses-k1", "opencode", true));
        when(knowledgePort.retrieve(anyString(), eq(5))).thenReturn(List.of(
                new KnowledgeHit("QA", "第一单", "用哪个框架?", "问：用哪个框架?\n答：React"),
                new KnowledgeHit("BUG", "第一单", "登录 500", "【标题】登录 500")));

        appService.dispatchTask(project.getId(), new ProjectAgentTaskCommand("梳理电商需求", RolePreset.DEV));

        // 检索 query = 任务 prompt 全文、topK = 配置默认（5）
        verify(knowledgePort).retrieve("梳理电商需求", 5);

        // 下发 prompt：命中前置注入（跨项目来源标注），原任务收在【本次任务】节
        ArgumentCaptor<AgentTaskDispatchCommand> command =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        ArgumentCaptor<AgentRunContext> runContext = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(agentTaskAppService).dispatch(anyString(), command.capture(), runContext.capture());
        assertThat(command.getValue().prompt())
                .contains("【平台知识库·相似历史沉淀】")
                .contains("〔QA｜第一单〕用哪个框架?")
                .contains("【标题】登录 500")
                .contains("【本次任务】")
                .endsWith("梳理电商需求");

        // SSE knowledge-retrieved（帧序 role-assigned → knowledge-retrieved → task-start）：
        // payload = projectId + runId + items[{kind, projectName, title, snippet}]
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(streamAppService).publish(eq(AgentEventTypes.KNOWLEDGE_RETRIEVED),
                payload.capture());
        assertThat(payload.getValue())
                .containsEntry("projectId", project.getId().toString())
                .containsEntry("runId", runContext.getValue().runId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) payload.getValue().get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0))
                .containsEntry("kind", "QA")
                .containsEntry("projectName", "第一单")
                .containsEntry("title", "用哪个框架?")
                .containsEntry("snippet", "问：用哪个框架?\n答：React");
    }

    @Test
    void given_no_hits_when_dispatch_then_prompt_untouched_and_no_knowledge_sse() {
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_BA);
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-k2", "ses-k2", "opencode", true));
        when(knowledgePort.retrieve(anyString(), eq(5))).thenReturn(List.of());

        appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("梳理需求", RolePreset.DEV));

        // 空命中：原 prompt 照发、不发 knowledge-retrieved（role-assigned 照发）
        ArgumentCaptor<AgentTaskDispatchCommand> command =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        verify(agentTaskAppService).dispatch(anyString(), command.capture(), any());
        assertThat(command.getValue().prompt()).isEqualTo("梳理需求");
        verify(streamAppService, never()).publish(eq(AgentEventTypes.KNOWLEDGE_RETRIEVED), any());
        verify(streamAppService).publish(eq(AgentEventTypes.ROLE_ASSIGNED), any());
    }

    @Test
    void given_retrieve_failure_when_dispatch_then_empty_injection_run_still_dispatched() {
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_BA);
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-k3", "ses-k3", "opencode", true));
        when(knowledgePort.retrieve(anyString(), eq(5)))
                .thenThrow(new RuntimeException("embedding 服务不可用"));

        ProjectAgentTaskResponse response = appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("梳理需求", RolePreset.DEV));

        // 检索降级为空注入（A5 §3）：run 照常下发，计数照常
        ArgumentCaptor<AgentTaskDispatchCommand> command =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        verify(agentTaskAppService).dispatch(anyString(), command.capture(), any());
        assertThat(command.getValue().prompt()).isEqualTo("梳理需求");
        assertThat(response.accepted()).isTrue();
        assertThat(openIteration(project).getStageTaskCount()).isEqualTo(1);
        verify(streamAppService, never()).publish(eq(AgentEventTypes.KNOWLEDGE_RETRIEVED), any());
    }

    @Test
    void given_knowledge_hits_when_dispatch_fix_run_then_prompt_injected() {
        // 修复 run 经同一注入缝（A5 §3：测试阶段命中历史 Bug 的叙事兑现点）
        Project project = persistedProject("opencode");
        when(agentTaskAppService.dispatch(anyString(), any(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-k4", "ses-k4", "opencode", true));
        when(knowledgePort.retrieve(anyString(), eq(5))).thenReturn(List.of(
                new KnowledgeHit("BUG", "第一单", "登录 500", "【标题】登录 500")));

        appService.dispatchFixRun(project.getId(), "请修复登录 500", "run-k4", null);

        ArgumentCaptor<AgentTaskDispatchCommand> command =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        verify(agentTaskAppService).dispatch(anyString(), command.capture(), any(), any());
        assertThat(command.getValue().prompt())
                .contains("〔BUG｜第一单〕登录 500")
                .endsWith("请修复登录 500");
        verify(streamAppService).publish(eq(AgentEventTypes.KNOWLEDGE_RETRIEVED), any());
    }

    // ---------- A6 §3 计量维度：iterationId 快照语义 ----------

    @Test
    void given_open_iteration_when_dispatch_fix_run_then_dims_carry_iteration_id() {
        // 期内修复 run：归期——dims 带 iterationId（run 发起时快照）+ role=FIX
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_TEST);
        when(agentTaskAppService.dispatch(anyString(), any(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-i1", "ses-i1", "opencode", true));
        when(knowledgePort.retrieve(anyString(), eq(5))).thenReturn(List.of());

        appService.dispatchFixRun(project.getId(), "修复回归失败", "run-i1", null);

        ArgumentCaptor<AgentRunContext> runContext = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(agentTaskAppService).dispatch(anyString(), any(), runContext.capture(), any());
        assertThat(runContext.getValue().usageContext().dims())
                .containsEntry("role", ProjectAgentTaskAppService.FIX_ROLE_DIM)
                .containsEntry("stage", ProjectMainChain.STAGE_TEST)
                .containsEntry("iterationId", openIteration(project).getId().toString());
    }

    @Test
    void given_no_open_iteration_when_dispatch_fix_run_then_dims_without_iteration_id() {
        // 期后修复 run（A6 §3 / A3 §7）：无 OPEN 期 → dims 不带 iterationId——
        // 归项目不归期（按期聚合不含它、项目总量含它，收口期成本定格）
        Project project = persistedProject("opencode");
        when(agentTaskAppService.dispatch(anyString(), any(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-i2", "ses-i2", "opencode", true));
        when(knowledgePort.retrieve(anyString(), eq(5))).thenReturn(List.of());

        appService.dispatchFixRun(project.getId(), "期后修个遗留问题", "run-i2", null);

        ArgumentCaptor<AgentRunContext> runContext = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(agentTaskAppService).dispatch(anyString(), any(), runContext.capture(), any());
        assertThat(runContext.getValue().usageContext().dims())
                .containsEntry("role", ProjectAgentTaskAppService.FIX_ROLE_DIM)
                .containsEntry("stage", ProjectMainChain.STAGE_CLOSED)
                .doesNotContainKey("iterationId");
    }

    // ---------- 运行终止（#38） ----------

    @Test
    void given_project_when_cancel_run_then_bridged_with_project_correlation() {
        // 票 #38：寻址转底座 cancelRun（runId 解析/abort/收口/帧序在
        // AgentTaskAppServiceTest 覆盖），projectId 关联随帧注入
        Project project = persistedProject("opencode");

        appService.cancelRun(project.getId(), "run-42");

        verify(agentTaskAppService).cancelRun(Long.toString(project.getWorkspaceId()),
                "run-42", Map.of(AgentStreamAppService.PROJECT_FIELD,
                        Long.toString(project.getId())));
        verifyNoInteractions(streamAppService, knowledgePort, baInterviewAppService);
    }

    @Test
    void given_missing_project_when_cancel_run_then_prj_001() {
        assertThatThrownBy(() -> appService.cancelRun(999L, "run-42"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- 测试数据 ----------

    private Project persistedProject(String engine) {
        return projectRepository.save(Project.create("测试项目", ProjectType.WEBSITE, engine,
                9001L + System.nanoTime() % 1000, null));
    }

    /** 引擎会话响应（#46 Demo 会话寻址的查询面形状；workspaceId/lastRunId 不参与分流）。 */
    private static AgentSessionResponse sessionOf(String sessionId, String engine) {
        return new AgentSessionResponse(sessionId, "w", engine, "run-old", null);
    }

    private Iteration persistedIteration(Project project, String stage) {
        return iterationRepository.save(Iteration.open(project.getId(), Iteration.FIRST_SEQ, stage));
    }

    private Iteration openIteration(Project project) {
        return iterationRepository
                .findByProjectIdAndStatus(project.getId(), IterationStatus.OPEN)
                .orElseThrow();
    }
}
