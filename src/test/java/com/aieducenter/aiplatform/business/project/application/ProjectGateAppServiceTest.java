package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.CartisanException;

import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectAgentTaskCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectAgentTaskResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.port.OpenBugQueryPort;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 门操作与收口（片5b 验收：门禁 409 / 驳回停留留痕 / G1 自动 Demo / G4 收口）：
 * approve = 引擎计数 ∧ 业务谓词（G3 经 {@link OpenBugQueryPort} 缝，#26 提供实现）
 * → 留痕 + 期迁移/收口 → SSE stage-changed；reject 一律停留带 reason。
 * 主链推进语义（计数门禁/终态收口）归 base.process StageAdvanceServiceTest。
 */
@SpringBootTest
class ProjectGateAppServiceTest {

    @Autowired
    private ProjectGateAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private IterationRepository iterationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProjectAgentTaskAppService agentTaskAppService;

    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    /** G3 业务谓词端口：默认无 Bug（NoopOpenBugQueryAdapter 同行为，mock 以便验证调用）。 */
    @MockitoBean
    private OpenBugQueryPort openBugQueryPort;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_confirmations");
        jdbcTemplate.update("DELETE FROM prj_iterations");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_ba_without_task_when_approve_then_prj_007_and_nothing_happens() {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 0);

        assertThatThrownBy(() -> appService.approve(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("门禁不足");

        // 门不放行：无留痕、阶段停留、无 SSE、无自动任务
        assertThat(confirmationCount()).isZero();
        assertThat(openIteration(projectId).getStage()).isEqualTo(ProjectMainChain.STAGE_BA);
        verifyNoInteractions(notificationAppService, agentTaskAppService);
    }

    @Test
    void given_ba_with_task_when_approve_then_advance_demo_confirmation_and_auto_demo()
            throws Exception {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 1);
        stubAutoDispatch("run-demo", ProjectMainChain.STAGE_DEMO);

        ProjectDetailResponse response = asUser(42L, () -> appService.approve(projectId));

        // 推进 DEMO：计数归零，状态仍开发中
        Iteration iteration = openIteration(projectId);
        assertThat(iteration.getStage()).isEqualTo(ProjectMainChain.STAGE_DEMO);
        assertThat(iteration.getStageTaskCount()).isZero();
        assertThat(response.stage()).isEqualTo(ProjectMainChain.STAGE_DEMO);
        assertThat(response.status()).isEqualTo(ProjectResponse.STATUS_IN_PROGRESS);

        // approve 也留痕（kind=需求确认、decision=通过、account_id 记 approver）
        Map<String, Object> row = soleConfirmationRow();
        assertThat(row.get("kind")).isEqualTo(1);
        assertThat(row.get("decision")).isEqualTo(1);
        assertThat(row.get("account_id")).isEqualTo(42L);
        assertThat(row.get("reason")).isNull();
        assertThat(((Number) row.get("iteration_id")).longValue())
                .isEqualTo(iteration.getId());

        // SSE：stage-changed(approved=true, stage=DEMO)
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationAppService).publish(eq(ProjectEventTypes.STAGE_CHANGED),
                payload.capture());
        assertThat(payload.getValue())
                .containsEntry("projectId", projectId.toString())
                .containsEntry("stage", ProjectMainChain.STAGE_DEMO)
                .containsEntry("stageLabel", "Demo")
                .containsEntry("approved", true)
                .doesNotContainKey("rejected")
                .doesNotContainKey("reason");

        // G1 前缀段自动：需求确认通过 → 自动跑 Demo（A3 §2.3）
        verify(agentTaskAppService).dispatchTask(projectId,
                new ProjectAgentTaskCommand(RolePreset.DEMO_KICKOFF_PROMPT, RolePreset.DEMO));
    }

    @Test
    void given_demo_confirmed_when_approve_then_advance_dev_without_auto_dispatch() {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_DEMO, 1);

        ProjectDetailResponse response = appService.approve(projectId);

        // 开发起全手动（A3 §2.3）：G2 通过不自动发任务
        assertThat(response.stage()).isEqualTo(ProjectMainChain.STAGE_DEV);
        assertThat(openIteration(projectId).getStageTaskCount()).isZero();
        verify(agentTaskAppService, never()).dispatchTask(anyLong(), any());
        verify(notificationAppService).publish(eq(ProjectEventTypes.STAGE_CHANGED), any());
    }

    @Test
    void given_dev_stage_when_approve_then_prj_009_no_gate() {
        // 开发段无门：推进归编排触发（首个测试任务），不是人拍板
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_DEV, 3);

        assertThatThrownBy(() -> appService.approve(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.STAGE_NO_GATE.message());

        assertThat(confirmationCount()).isZero();
        assertThat(openIteration(projectId).getStage()).isEqualTo(ProjectMainChain.STAGE_DEV);
    }

    @Test
    void given_open_bugs_when_approve_test_gate_then_prj_008_stays() {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_TEST, 1);
        when(openBugQueryPort.hasOpenBugs(projectId)).thenReturn(true);

        assertThatThrownBy(() -> appService.approve(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GATE_OPEN_BUGS.message());

        // 业务谓词不满足：停留测试段、无留痕（拍板未成立）
        assertThat(openIteration(projectId).getStage()).isEqualTo(ProjectMainChain.STAGE_TEST);
        assertThat(confirmationCount()).isZero();
    }

    @Test
    void given_no_open_bugs_when_approve_test_gate_then_advance_acceptance() {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_TEST, 1);
        when(openBugQueryPort.hasOpenBugs(projectId)).thenReturn(false);

        ProjectDetailResponse response = appService.approve(projectId);

        assertThat(response.stage()).isEqualTo(ProjectMainChain.STAGE_ACCEPTANCE);
        // G3 过门留痕（kind=开发完成确认）
        assertThat(soleConfirmationRow().get("kind")).isEqualTo(3);
        verify(openBugQueryPort).hasOpenBugs(projectId);
    }

    @Test
    void given_acceptance_passed_when_approve_then_iteration_closed_project_delivered() {
        // 验收门 minTasks=0：无需任务即可拍板（A3 §2.4）
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_ACCEPTANCE, 0);

        ProjectDetailResponse response = appService.approve(projectId);

        // G4 通过即收口（A3 §2.2 无交付段）：期 CLOSED + 项目已交付（派生）
        Iteration iteration = iterationRepository
                .findByProjectIdAndStatus(projectId, IterationStatus.OPEN).orElse(null);
        assertThat(iteration).isNull();
        Map<String, Object> closedRow = jdbcTemplate.queryForMap(
                "SELECT stage, status, closed_at FROM prj_iterations WHERE project_id = ?",
                projectId);
        assertThat(closedRow.get("stage")).isEqualTo(ProjectMainChain.STAGE_CLOSED);
        assertThat(closedRow.get("status")).isEqualTo(2);
        assertThat(closedRow.get("closed_at")).isNotNull();
        assertThat(response.stage()).isEqualTo(ProjectMainChain.STAGE_CLOSED);
        assertThat(response.status()).isEqualTo(ProjectResponse.STATUS_DELIVERED);
        assertThat(response.statusLabel()).isEqualTo("已交付");
        assertThat(response.stageTaskCount()).isNull();

        // 收口照常发 stage-changed（stage=关闭，前端重拉 REST，A3 §5）
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationAppService).publish(eq(ProjectEventTypes.STAGE_CHANGED),
                payload.capture());
        assertThat(payload.getValue())
                .containsEntry("stage", ProjectMainChain.STAGE_CLOSED)
                .containsEntry("stageLabel", "关闭")
                .containsEntry("approved", true);
    }

    @Test
    void given_no_open_iteration_when_approve_then_prj_010() {
        Long projectId = persistedProject();

        assertThatThrownBy(() -> appService.approve(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.ITERATION_NOT_OPEN.message());
    }

    @Test
    void given_missing_project_when_approve_then_prj_001() {
        assertThatThrownBy(() -> appService.approve(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_auto_demo_failure_when_approve_then_gate_kept() {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 1);
        when(agentTaskAppService.dispatchTask(anyLong(), any()))
                .thenThrow(new RuntimeException("引擎不可用"));

        ProjectDetailResponse response = appService.approve(projectId);

        // 门决策不因自动 Demo 起跑失败回滚（阶段已推进，留痕已落）
        assertThat(response.stage()).isEqualTo(ProjectMainChain.STAGE_DEMO);
        assertThat(confirmationCount()).isEqualTo(1);
    }

    @Test
    void given_reason_when_reject_then_stay_with_confirmation_and_sse_reason() throws Exception {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_ACCEPTANCE, 0);

        ProjectDetailResponse response = asUser(7L, () ->
                appService.reject(projectId, " 首页布局与 PRD 不符 "));

        // 驳回一律停留当前阶段（验收驳回停留验收段，A3 §3）
        assertThat(response.stage()).isEqualTo(ProjectMainChain.STAGE_ACCEPTANCE);
        assertThat(response.status()).isEqualTo(ProjectResponse.STATUS_IN_PROGRESS);
        assertThat(openIteration(projectId).getStage()).isEqualTo(ProjectMainChain.STAGE_ACCEPTANCE);

        // 留痕：kind=验收、decision=驳回、reason 落库、account_id 记驳回人
        Map<String, Object> row = soleConfirmationRow();
        assertThat(row.get("kind")).isEqualTo(4);
        assertThat(row.get("decision")).isEqualTo(2);
        assertThat(row.get("reason")).isEqualTo("首页布局与 PRD 不符");
        assertThat(row.get("account_id")).isEqualTo(7L);

        // SSE：stage-changed(rejected=true, reason)——前端展示驳回理由
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationAppService).publish(eq(ProjectEventTypes.STAGE_CHANGED),
                payload.capture());
        assertThat(payload.getValue())
                .containsEntry("projectId", projectId.toString())
                .containsEntry("stage", ProjectMainChain.STAGE_ACCEPTANCE)
                .containsEntry("stageLabel", "验收")
                .containsEntry("rejected", true)
                .containsEntry("reason", "首页布局与 PRD 不符")
                .doesNotContainKey("approved");
    }

    @Test
    void given_blank_reason_when_reject_then_prj_011_no_row() {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 1);

        // reason 必填由留痕不变量兜底（DomainException，REST 面 @NotBlank 先行同码）
        assertThatThrownBy(() -> appService.reject(projectId, " "))
                .isInstanceOf(CartisanException.class)
                .hasMessageContaining(ProjectMessage.REJECT_REASON_REQUIRED.message());

        assertThat(confirmationCount()).isZero();
        verifyNoInteractions(notificationAppService);
    }

    @Test
    void given_dev_stage_when_reject_then_prj_009() {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_DEV, 1);

        assertThatThrownBy(() -> appService.reject(projectId, "方向不对"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.STAGE_NO_GATE.message());
    }

    @Test
    void given_missing_project_when_reject_then_prj_001() {
        assertThatThrownBy(() -> appService.reject(-1L, "不对"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- 测试数据 ----------

    private Long persistedProject() {
        return projectRepository.save(Project.create("门测试", ProjectType.WEBSITE, "opencode",
                9300L, null)).getId();
    }

    private Long persistedProjectWithIteration(String stage, int taskCount) {
        Project project = projectRepository.save(Project
                .create("门测试", ProjectType.WEBSITE, "opencode", 9301L, null));
        Iteration iteration = Iteration.open(project.getId(), Iteration.FIRST_SEQ, stage);
        for (int i = 0; i < taskCount; i++) {
            iteration.recordStageTask();
        }
        iterationRepository.save(iteration);
        return project.getId();
    }

    private Iteration openIteration(Long projectId) {
        return iterationRepository
                .findByProjectIdAndStatus(projectId, IterationStatus.OPEN).orElseThrow();
    }

    private long confirmationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prj_confirmations", Long.class);
    }

    private Map<String, Object> soleConfirmationRow() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT iteration_id, kind, decision, reason, account_id, decided_at "
                        + "FROM prj_confirmations");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private void stubAutoDispatch(String runId, String stage) {
        when(agentTaskAppService.dispatchTask(anyLong(), any())).thenReturn(
                new ProjectAgentTaskResponse(runId, "ses-1", "opencode", "DEMO",
                        "原型开发工程师", stage, true));
    }

    /** 拍板人以会话上下文注入（account_id 从第一天记 approver，A3 §3）。 */
    private <T> T asUser(Long userId, RequestContextCall<T> call) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, userId, "gate-test", null, null),
                call::get);
    }

    @FunctionalInterface
    private interface RequestContextCall<T> {
        T get();
    }
}
