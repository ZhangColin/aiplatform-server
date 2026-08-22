package com.aieducenter.aiplatform.business.project.application;

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
import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentTaskAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentTaskDispatchCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentTaskResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
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

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_iterations");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_open_ba_iteration_when_dispatch_without_role_then_stage_default_and_counted() {
        Project project = persistedProject("opencode");
        persistedIteration(project, ProjectMainChain.STAGE_BA);
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-1", "ses-1", "opencode", true));

        ProjectAgentTaskResponse response = appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("梳理需求", null));

        assertThat(response.role()).isEqualTo(RolePreset.BA);
        assertThat(response.roleName()).isEqualTo("需求分析师");
        assertThat(response.stage()).isEqualTo(ProjectMainChain.STAGE_BA);
        assertThat(response.runId()).isEqualTo("run-1");
        assertThat(response.accepted()).isTrue();

        // 下发命令：BA 角色卡入参 + 引擎随项目；编排上下文：业务 runId + 计量归属 + projectId 关联
        ArgumentCaptor<AgentTaskDispatchCommand> command =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        ArgumentCaptor<AgentRunContext> runContext = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(agentTaskAppService).dispatch(eq(project.getWorkspaceId().toString()),
                command.capture(), runContext.capture());
        assertThat(command.getValue().systemPrompt()).isEqualTo(RolePreset.BA.systemPrompt());
        assertThat(command.getValue().modelId()).isEqualTo(RolePreset.BA.modelId());
        assertThat(command.getValue().engine()).isEqualTo("opencode");
        assertThat(runContext.getValue().runId()).isNotBlank();

        // role-assigned 先于 run 下发发射（帧序 role-assigned → task-start → …；runId 与编排上下文同值）
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(streamAppService).publish(eq(AgentEventTypes.ROLE_ASSIGNED), payload.capture());
        assertThat(payload.getValue()).containsEntry("projectId", project.getId().toString())
                .containsEntry("runId", runContext.getValue().runId())
                .containsEntry("role", "BA")
                .containsEntry("stage", ProjectMainChain.STAGE_BA)
                .containsEntry("engine", "opencode");
        assertThat(runContext.getValue().usageContext().subject())
                .isEqualTo(project.getId().toString());
        assertThat(runContext.getValue().usageContext().dims())
                .containsEntry("role", "BA").containsEntry("stage", ProjectMainChain.STAGE_BA);
        assertThat(runContext.getValue().streamCorrelation())
                .containsEntry("projectId", project.getId().toString());

        // 接受即计数：BA 段计数 0 → 1（门禁输入）
        assertThat(openIteration(project).getStageTaskCount()).isEqualTo(1);
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
        verify(agentTaskAppService).dispatch(anyString(), command.capture(), any());
        assertThat(command.getValue().systemPrompt()).isEqualTo(RolePreset.DEV.systemPrompt());
        assertThat(command.getValue().modelId()).isEqualTo(RolePreset.DEV.modelId());
        assertThat(command.getValue().engine()).isEqualTo("dsh");
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
        persistedIteration(project, ProjectMainChain.STAGE_BA);
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-4", null, "opencode", false));

        ProjectAgentTaskResponse response = appService.dispatchTask(project.getId(),
                new ProjectAgentTaskCommand("梳理需求", null));

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

    // ---------- 测试数据 ----------

    private Project persistedProject(String engine) {
        return projectRepository.save(Project.create("测试项目", ProjectType.WEBSITE, engine,
                9001L + System.nanoTime() % 1000, null));
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
