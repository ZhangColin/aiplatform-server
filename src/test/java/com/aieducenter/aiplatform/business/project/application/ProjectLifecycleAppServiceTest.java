package com.aieducenter.aiplatform.business.project.application;

import java.net.URI;
import java.time.LocalDateTime;
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

import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.business.project.application.dto.command.CreateProjectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectAgentTaskCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectAgentTaskResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectCreatedResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectPreviewResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目生命周期用例（片5a 验收第 1/4 步的编排面）：建项目 = 工作区副作用 →
 * 一事务 Project + 第 1 期（BA/OPEN）→ SSE workspace-created + stage-changed →
 * 自动跑 BA；删除真删级联 + workspace-destroyed；列表/详情的派生状态。
 * Docker 链路在 WorkspaceLifecycleAppServiceTest（mock 工作区服务，聚焦编排）。
 */
@SpringBootTest
class ProjectLifecycleAppServiceTest {

    @Autowired
    private ProjectLifecycleAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private IterationRepository iterationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @MockitoBean
    private ProjectAgentTaskAppService agentTaskAppService;

    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_iterations");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_valid_command_when_create_then_workspace_iteration_sse_and_auto_ba() {
        stubWorkspace("9100", "aiplatform-dev-100");
        when(agentTaskAppService.dispatchTask(any(), any())).thenReturn(
                new ProjectAgentTaskResponse("run-1", "ses-1", "opencode", "BA",
                        "需求分析师", ProjectMainChain.STAGE_BA, true));

        ProjectCreatedResponse response = appService.create(
                new CreateProjectCommand("官网 demo", null, "opencode", "做一个官网"));

        // 工作区副作用先行：dev 工作区
        verify(workspaceLifecycleAppService).create(new CreateWorkspaceCommand(EnvKind.DEV));

        // 一事务 Project + 第 1 期：BA / OPEN / seq=1
        Long projectId = Long.parseLong(response.project().id());
        assertThat(projectRepository.findById(projectId)).isPresent();
        Iteration iteration = iterationRepository
                .findByProjectIdAndStatus(projectId, IterationStatus.OPEN).orElseThrow();
        assertThat(iteration.getSeq()).isEqualTo(1);
        assertThat(iteration.getStage()).isEqualTo(ProjectMainChain.STAGE_BA);
        assertThat(response.project().name()).isEqualTo("官网 demo");
        assertThat(response.project().type()).isEqualTo(ProjectType.WEBSITE); // 类型缺省官网
        assertThat(response.project().engine()).isEqualTo("opencode");
        assertThat(response.project().workspaceId()).isEqualTo("9100");
        assertThat(response.project().status()).isEqualTo(ProjectResponse.STATUS_IN_PROGRESS);
        assertThat(response.runId()).isEqualTo("run-1"); // 自动 BA 运行标识随响应返回
        assertThat(response.accepted()).isTrue();

        // SSE（副作用落定后）：workspace-created → stage-changed(BA)，帧序先通知后阶段
        InOrder sseOrder = inOrder(notificationAppService);
        ArgumentCaptor<Map<String, Object>> created =
                ArgumentCaptor.forClass(Map.class);
        sseOrder.verify(notificationAppService).publish(eq(ProjectEventTypes.WORKSPACE_CREATED),
                created.capture());
        assertThat(created.getValue())
                .containsEntry("projectId", projectId.toString())
                .containsEntry("projectName", "官网 demo")
                .containsEntry("container", "aiplatform-dev-100")
                .containsEntry("projectType", "WEBSITE")
                .containsEntry("engine", "opencode");
        ArgumentCaptor<Map<String, Object>> stage = ArgumentCaptor.forClass(Map.class);
        sseOrder.verify(notificationAppService).publish(eq(ProjectEventTypes.STAGE_CHANGED),
                stage.capture());
        assertThat(stage.getValue())
                .containsEntry("projectId", projectId.toString())
                .containsEntry("stage", ProjectMainChain.STAGE_BA)
                .containsEntry("stageLabel", "需求梳理");

        // 前缀段自动：BA 角色任务（初始描述即首条任务内容）
        verify(agentTaskAppService).dispatchTask(projectId,
                new ProjectAgentTaskCommand("做一个官网", RolePreset.BA));
    }

    @Test
    void given_blank_requirement_when_create_then_default_kickoff_prompt() {
        stubWorkspace("9101", "aiplatform-dev-101");
        when(agentTaskAppService.dispatchTask(any(), any())).thenReturn(
                new ProjectAgentTaskResponse("run-2", "ses-2", "opencode", "BA",
                        "需求分析师", ProjectMainChain.STAGE_BA, true));

        ProjectCreatedResponse response = appService.create(
                new CreateProjectCommand("商城", ProjectType.ECOMMERCE, "dsh", " "));

        assertThat(response.project().type()).isEqualTo(ProjectType.ECOMMERCE);
        // 空需求描述 → 缺省开场提示（对话展开起点）
        verify(agentTaskAppService).dispatchTask(Long.parseLong(response.project().id()),
                new ProjectAgentTaskCommand(RolePreset.DEFAULT_KICKOFF_PROMPT,
                        RolePreset.BA));
    }

    @Test
    void given_unknown_engine_when_create_then_prj_002_and_no_workspace_side_effect() {
        assertThatThrownBy(() -> appService.create(
                new CreateProjectCommand("官网", null, "codex", null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.ENGINE_UNKNOWN.message());

        // 引擎校验先于 Docker 副作用
        verify(workspaceLifecycleAppService, never()).create(any());
        verifyNoRows();
    }

    @Test
    void given_auto_ba_failure_when_create_then_project_kept_and_not_accepted() {
        stubWorkspace("9102", "aiplatform-dev-102");
        when(agentTaskAppService.dispatchTask(any(), any()))
                .thenThrow(new RuntimeException("引擎不可用"));

        ProjectCreatedResponse response = appService.create(
                new CreateProjectCommand("官网 demo", null, "opencode", null));

        // BA 起跑失败不回滚建项目（项目已成立）
        assertThat(response.accepted()).isFalse();
        assertThat(response.runId()).isNull();
        assertThat(projectRepository.count()).isEqualTo(1);
    }

    @Test
    void given_projects_when_list_then_desc_order_with_derived_status() {
        Project first =
                projectRepository.save(Project
                        .create("老项目", ProjectType.WEBSITE, "opencode", 1L, null));
        iterationRepository.save(Iteration.open(first.getId(), 1,
                ProjectMainChain.STAGE_BA));
        projectRepository.save(Project
                .create("新项目（无期）", ProjectType.ECOMMERCE, "dsh", 2L, null));

        List<ProjectResponse> list = appService.list();

        // 有 OPEN 期 = 开发中；无 = 已交付（派生投影，A3 §1）
        assertThat(list).extracting(ProjectResponse::name)
                .containsExactly("新项目（无期）", "老项目");
        assertThat(list).extracting(ProjectResponse::status)
                .containsExactly(ProjectResponse.STATUS_DELIVERED,
                        ProjectResponse.STATUS_IN_PROGRESS);
        assertThat(list).extracting(ProjectResponse::stageLabel)
                .containsExactly(null, "需求梳理");
    }

    @Test
    void given_project_when_delete_then_workspace_destroyed_rows_gone_sse_emitted() {
        Long projectId = persistedProjectWithIteration("9200");

        appService.delete(projectId);

        // 真删级联：工作区销毁（容器/网络/卷）+ prj_* 行删除
        verify(workspaceLifecycleAppService).destroy("9200");
        verifyNoRows();
        verify(notificationAppService).publish(eq(ProjectEventTypes.WORKSPACE_DESTROYED),
                eq(Map.of("projectId", projectId.toString())));
    }

    @Test
    void given_workspace_destroy_failure_when_delete_then_rows_deleted_anyway() {
        Long projectId = persistedProjectWithIteration("9201");
        org.mockito.Mockito.doThrow(new RuntimeException("docker down"))
                .when(workspaceLifecycleAppService).destroy(anyString());

        appService.delete(projectId);

        // 物理销毁失败不阻断记录删除（真删级联优先，物理残留可重试）
        verifyNoRows();
        verify(notificationAppService).publish(eq(ProjectEventTypes.WORKSPACE_DESTROYED),
                eq(Map.of("projectId", projectId.toString())));
    }

    @Test
    void given_missing_project_when_delete_then_prj_001() {
        assertThatThrownBy(() -> appService.delete(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_project_when_preview_then_url_exposed_and_sse_preview_ready() throws Exception {
        Long projectId = persistedProjectWithIteration("9300");
        when(workspaceLifecycleAppService.exposePreview("9300"))
                .thenReturn(new URI("http://localhost:30080"));

        ProjectPreviewResponse response =
                appService.preview(projectId);

        // 端口真实暴露（docker publish 先行）→ 返回可访问 URL
        assertThat(response.url()).isEqualTo("http://localhost:30080");
        // SSE preview-ready（projectId + url，A1 §4 口子④的业务呈现）
        verify(notificationAppService).publish(eq(ProjectEventTypes.PREVIEW_READY),
                eq(Map.of("projectId", projectId.toString(), "url", "http://localhost:30080")));
    }

    @Test
    void given_closed_iteration_when_get_then_stage_closed_and_delivered() {
        Project project = projectRepository.save(Project
                .create("已收口项目", ProjectType.WEBSITE, "opencode", 9302L, null));
        Iteration iteration = iterationRepository.save(Iteration.open(project.getId(),
                Iteration.FIRST_SEQ, ProjectMainChain.STAGE_ACCEPTANCE));
        iteration.close(ProjectMainChain.STAGE_CLOSED);
        iterationRepository.save(iteration);

        ProjectResponse response = appService.get(project.getId());

        // 收口后的期位置回溯（A3 §5）：stage=CLOSED，派生已交付
        assertThat(response.stage()).isEqualTo(ProjectMainChain.STAGE_CLOSED);
        assertThat(response.stageLabel()).isEqualTo("关闭");
        assertThat(response.status()).isEqualTo(ProjectResponse.STATUS_DELIVERED);
        assertThat(response.stageTaskCount()).isNull();
    }

    // ---------- 测试数据 ----------

    private void stubWorkspace(String workspaceId, String containerName) {
        when(workspaceLifecycleAppService.create(any())).thenReturn(new WorkspaceResponse(
                workspaceId, EnvKind.DEV, "开发环境", containerName, "net-x", 14096, 18081,
                List.of(), LocalDateTime.now()));
    }

    private Long persistedProjectWithIteration(String workspaceId) {
        Project project =
                projectRepository.save(Project
                        .create("删除对象", ProjectType.WEBSITE, "opencode",
                                Long.parseLong(workspaceId), null));
        iterationRepository.save(Iteration.open(project.getId(), 1,
                ProjectMainChain.STAGE_BA));
        return project.getId();
    }

    private void verifyNoRows() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prj_projects", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prj_iterations", Long.class))
                .isZero();
    }
}
