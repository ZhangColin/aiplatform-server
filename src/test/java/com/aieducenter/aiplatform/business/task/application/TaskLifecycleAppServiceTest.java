package com.aieducenter.aiplatform.business.task.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.CartisanException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext;
import com.aieducenter.aiplatform.base.agentengine.application.AgentTaskAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentTaskDispatchCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentTaskResponse;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;
import com.aieducenter.aiplatform.business.project.application.ProjectEventTypes;
import com.aieducenter.aiplatform.business.project.application.ProjectKnowledgeAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.port.OpenBugQueryPort;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.business.task.application.dto.command.CreateTaskCommand;
import com.aieducenter.aiplatform.business.task.application.dto.command.SubmitTaskCommand;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskDetailResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskResponse;
import com.aieducenter.aiplatform.business.task.application.event.TaskCompleted;
import com.aieducenter.aiplatform.business.task.domain.aggregate.Bug;
import com.aieducenter.aiplatform.business.task.domain.enums.BugSeverity;
import com.aieducenter.aiplatform.business.task.domain.enums.TaskStatus;
import com.aieducenter.aiplatform.business.task.domain.error.TaskMessage;
import com.aieducenter.aiplatform.business.task.domain.repository.BugRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 任务生命周期（票 #26 验收主面，A4 §2/§3/§5）：建任务处开发→测试 advance
 * 守卫；start/submit 指派守卫与载荷两形状；**确认一事务内 Bug 入库（首轮
 * OPEN）/复测翻态（VERIFIED/退回 OPEN）+ TaskCompleted（AFTER_COMMIT 送达，
 * 幂等以状态机守门）**；驳回/取消守卫与非法迁移 409 TASK_；空 Bug 清单 =
 * 无入库 ∧ G3 谓词直接就绪。修复派发与回填续跑随 #27。
 */
@SpringBootTest
@Import(TaskLifecycleAppServiceTest.EventRecorderConfig.class)
class TaskLifecycleAppServiceTest {

    private static final Long DEV_OWNER = 7700L;

    @Autowired
    private TaskLifecycleAppService appService;

    @Autowired
    private TaskQueryAppService queryAppService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private IterationRepository iterationRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BugRepository bugRepository;

    @Autowired
    private OpenBugQueryPort openBugQueryPort;

    @Autowired
    private TaskCompletedRecorder completedRecorder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    /** 底座编排入口 mock：confirm 触发的修复链不真发引擎（终态裁决在
     * FixDispatchAppServiceTest 覆盖，这里只断言触发与幂等）。 */
    @MockitoBean
    private AgentTaskAppService agentTaskAppService;

    /** 知识端口 mock（A5 §1 confirm 摄取挂钩验证；真实入库链路见 KnowledgeAppServiceTest）。 */
    @MockitoBean
    private KnowledgePort knowledgePort;

    @BeforeEach
    void stubDispatchAccepted() {
        org.mockito.Mockito.when(agentTaskAppService.dispatch(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> new AgentTaskResponse(
                        invocation.getArgument(2, AgentRunContext.class).runId(),
                        "ses-fix", "opencode", true));
    }

    @AfterEach
    void tearDown() {
        completedRecorder.committed.clear();
        jdbcTemplate.update("DELETE FROM tsk_bugs");
        jdbcTemplate.update("DELETE FROM tsk_tasks");
        jdbcTemplate.update("DELETE FROM prj_confirmations");
        jdbcTemplate.update("DELETE FROM prj_iterations");
        jdbcTemplate.update("DELETE FROM prj_projects");
        jdbcTemplate.update("DELETE FROM idn_accounts");
    }

    // ---------- 建任务与期联动（A4 §5） ----------

    @Test
    void given_dev_stage_project_when_create_then_first_test_task_advances_to_test()
            throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_DEV, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");

        TaskResponse response = asUser(DEV_OWNER, () -> appService.create(projectId.toString(),
                new CreateTaskCommand("首轮回归", "全量回归 + 提交 Bug 清单", assignee)));

        assertThat(response.status()).isEqualByComparingTo(TaskStatus.PUBLISHED);
        assertThat(openIteration(projectId).getStage())
                .isEqualTo(ProjectMainChain.STAGE_TEST); // 开发→测试唯一触发
        assertThat(openIteration(projectId).getStageTaskCount()).isZero(); // 人任务不计数

        // SSE：stage-changed(编排推进) + task-updated(PUBLISHED)
        verify(notificationAppService).publish(eq(ProjectEventTypes.STAGE_CHANGED), anyMap());
        ArgumentCaptor<Map<String, Object>> taskUpdated = ArgumentCaptor.forClass(Map.class);
        verify(notificationAppService).publish(eq(TaskEventTypes.TASK_UPDATED),
                taskUpdated.capture());
        assertThat(taskUpdated.getValue())
                .containsEntry(TaskEventTypes.PROJECT_ID_FIELD, projectId.toString())
                .containsEntry(TaskEventTypes.TASK_ID_FIELD, response.taskId())
                .containsEntry(TaskEventTypes.STATUS_FIELD, "PUBLISHED");
    }

    @Test
    void given_test_stage_or_closed_project_when_create_then_stage_untouched() throws Exception {
        // 复测场景：已在测试段不动
        Long retestProject = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        asUser(DEV_OWNER, () -> appService.create(retestProject.toString(),
                new CreateTaskCommand("复测", "逐条复测", assignee)));
        assertThat(openIteration(retestProject).getStage())
                .isEqualTo(ProjectMainChain.STAGE_TEST);

        // 期后修复：无 OPEN 期不动
        Long closedProject = persistedClosedProject(DEV_OWNER);
        asUser(DEV_OWNER, () -> appService.create(closedProject.toString(),
                new CreateTaskCommand("期后修复", "收口后补测", assignee)));
        assertThat(iterationRepository.findByProjectId(closedProject))
                .allMatch(iteration -> iteration.getStatus() == IterationStatus.CLOSED);

        // 两次建任务都只有 task-updated，无 stage-changed
        verify(notificationAppService, never())
                .publish(eq(ProjectEventTypes.STAGE_CHANGED), anyMap());
    }

    @Test
    void given_unknown_assignee_when_create_then_task_008_no_row() {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_DEV, DEV_OWNER);

        assertThatThrownBy(() -> asUser(DEV_OWNER, () -> appService.create(projectId.toString(),
                new CreateTaskCommand("标题", "内容", -1L))))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(TaskMessage.ASSIGNEE_NOT_FOUND.message());
        assertThat(taskCount()).isZero();
    }

    @Test
    void given_missing_project_when_create_then_prj_001() {
        Long assignee = persistedAccount("sub-opc", "外包测试");

        assertThatThrownBy(() -> asUser(DEV_OWNER, () -> appService.create("-1",
                new CreateTaskCommand("标题", "内容", assignee))))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("项目不存在");
    }

    // ---------- 主链路：start → submit（首轮）→ confirm（Bug 入库） ----------

    @Test
    void given_first_round_submission_when_confirm_then_bugs_open_and_completed_event()
            throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = createdTaskOf(projectId, assignee);

        asUser(assignee, () -> appService.start(taskId));
        asUser(assignee, () -> appService.submit(taskId, new SubmitTaskCommand(
                "首轮回归报告",
                List.of(new SubmitTaskCommand.BugPayload("登录 500", "提交后 500",
                        "1. 打开登录页", BugSeverity.CRITICAL)),
                null)));

        TaskDetailResponse detail = asUser(DEV_OWNER, () -> appService.confirm(taskId));

        // 任务终态
        assertThat(detail.task().status()).isEqualByComparingTo(TaskStatus.CONFIRMED);
        assertThat(detail.task().confirmedAt()).isNotNull();

        // Bug 一事务内入库：OPEN、severity/溯源键齐
        List<Map<String, Object>> rows = bugRows(projectId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("status", 1)
                .containsEntry("severity", 2)
                .containsEntry("title", "登录 500");

        // TaskCompleted：AFTER_COMMIT 送达，载荷带报告与 Bug 清单（#27 续跑素材）
        assertThat(completedRecorder.committed).hasSize(1);
        TaskCompleted event = completedRecorder.committed.get(0);
        assertThat(event.taskId()).isEqualTo(taskId.toString());
        assertThat(event.type()).isEqualTo("TEST");
        assertThat(event.assignee()).isEqualTo(assignee.toString());
        assertThat(event.summary()).contains("首轮回归报告").contains("登录 500");

        // SSE：task-updated(status=CONFIRMED)
        ArgumentCaptor<Map<String, Object>> confirmed = ArgumentCaptor.forClass(Map.class);
        verify(notificationAppService, times(4)).publish(eq(TaskEventTypes.TASK_UPDATED),
                confirmed.capture()); // create/start/submit/confirm 四次迁移，确认是最后一次
        assertThat(confirmed.getValue())
                .containsEntry(TaskEventTypes.PROJECT_ID_FIELD, projectId.toString())
                .containsEntry(TaskEventTypes.TASK_ID_FIELD, taskId.toString())
                .containsEntry(TaskEventTypes.STATUS_FIELD, "CONFIRMED");

        // G3 谓词（真实现）：未复测关闭 → 有未关闭 Bug
        assertThat(openBugQueryPort.hasOpenBugs(projectId)).isTrue();
    }

    @Test
    void given_empty_bug_list_when_confirm_then_no_bugs_and_g3_ready() throws Exception {
        // 空 Bug 清单允许（测试全过）：确认后无入库、G3 直接就绪（A4 §3）
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = submittedFirstRound(projectId, assignee, List.of());

        asUser(DEV_OWNER, () -> appService.confirm(taskId));

        assertThat(bugRows(projectId)).isEmpty();
        assertThat(openBugQueryPort.hasOpenBugs(projectId)).isFalse();
        assertThat(completedRecorder.committed).hasSize(1);
        assertThat(completedRecorder.committed.get(0).summary()).contains("测试全过");
    }

    @Test
    void given_repeat_confirm_when_already_confirmed_then_task_002_and_no_second_event()
            throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = submittedFirstRound(projectId, assignee, List.of());

        asUser(DEV_OWNER, () -> appService.confirm(taskId));
        assertThatThrownBy(() -> asUser(DEV_OWNER, () -> appService.confirm(taskId)))
                .isInstanceOfSatisfying(CartisanException.class, e ->
                        assertThat(e.getCodeMessage().code()).isEqualTo("TASK_002"));
        assertThat(completedRecorder.committed).hasSize(1); // 幂等以状态机守门
        assertThat(bugRows(projectId)).isEmpty(); // 重复确认不入库
    }

    // ---------- 复测确认：翻态（A4 §3） ----------

    @Test
    void given_retest_submission_when_confirm_then_verified_or_back_to_open() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = createdTaskOf(projectId, assignee);
        Bug fixed = bugRepository.save(Bug.fixedOf(projectId, taskId, "登录 500",
                "run-9", "已修复登录拦截"));
        Bug unfixed = bugRepository.save(Bug.fixedOf(projectId, taskId, "样式错位",
                "run-8", "已调整样式"));

        asUser(assignee, () -> appService.start(taskId));
        asUser(assignee, () -> appService.submit(taskId, new SubmitTaskCommand(
                "复测报告",
                null,
                List.of(new SubmitTaskCommand.RetestResultPayload(fixed.getId(), true, "已复现修复"),
                        new SubmitTaskCommand.RetestResultPayload(unfixed.getId(), false, "仍错位")))));

        TaskDetailResponse detail = asUser(DEV_OWNER, () -> appService.confirm(taskId));

        Map<String, Object> fixedRow = bugRow(fixed.getId());
        Map<String, Object> unfixedRow = bugRow(unfixed.getId());
        assertThat(fixedRow.get("status")).isEqualTo(3);   // VERIFIED：唯一关闭态
        assertThat(unfixedRow.get("status")).isEqualTo(1); // 退回 OPEN（再派发随 #27）
        assertThat(detail.bugs()).hasSize(2);

        assertThat(completedRecorder.committed).hasSize(1);
        assertThat(completedRecorder.committed.get(0).summary())
                .contains("复测报告").contains("通过").contains("未通过");

        // 一过一退：仍有未关闭 Bug → G3 不就绪
        assertThat(openBugQueryPort.hasOpenBugs(projectId)).isTrue();
    }

    // ---------- A5 §1 摄取挂钩：TEST_REPORT（报告分块）+ BUG（OPEN 时刻一次） ----------

    @Test
    void given_first_round_confirm_when_confirm_then_test_report_and_bugs_indexed()
            throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = submittedFirstRound(projectId, assignee,
                List.of(new SubmitTaskCommand.BugPayload("登录 500", "提交后 500",
                        "1. 打开登录页", BugSeverity.CRITICAL)));

        asUser(DEV_OWNER, () -> appService.confirm(taskId));

        // TEST_REPORT：source_ref = taskId，报告文本分块入库（title 带任务标题）
        // BUG：一条一块（标题/描述/复现步骤/严重级），source_ref = bugId、meta 带 severity
        ArgumentCaptor<KnowledgeSpec> spec = ArgumentCaptor.forClass(KnowledgeSpec.class);
        verify(knowledgePort, times(2)).index(spec.capture());
        KnowledgeSpec report = spec.getAllValues().stream()
                .filter(s -> ProjectKnowledgeAppService.KIND_TEST_REPORT.equals(s.kind()))
                .findFirst().orElseThrow();
        assertThat(report.sourceRef()).isEqualTo(taskId.toString());
        assertThat(report.title()).contains("回归测试");
        assertThat(report.chunks()).singleElement().isEqualTo("首轮报告");
        assertThat(report.meta()).containsEntry("taskId", taskId.toString());
        KnowledgeSpec bug = spec.getAllValues().stream()
                .filter(s -> ProjectKnowledgeAppService.KIND_BUG.equals(s.kind()))
                .findFirst().orElseThrow();
        assertThat(bug.sourceRef()).isEqualTo(bugRows(projectId).get(0).get("id").toString());
        assertThat(bug.title()).isEqualTo("登录 500");
        assertThat(bug.chunks()).singleElement().asString()
                .contains("【标题】登录 500").contains("【描述】提交后 500")
                .contains("【复现步骤】1. 打开登录页").contains("【严重级】严重");
        assertThat(bug.meta()).containsEntry("severity", "严重");
    }

    @Test
    void given_retest_confirm_when_confirm_then_report_only_no_bug_reindex() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = createdTaskOf(projectId, assignee);
        Bug fixed = bugRepository.save(Bug.fixedOf(projectId, taskId, "登录 500",
                "run-9", "已修复登录拦截"));

        asUser(assignee, () -> appService.start(taskId));
        asUser(assignee, () -> appService.submit(taskId, new SubmitTaskCommand(
                "复测报告", null,
                List.of(new SubmitTaskCommand.RetestResultPayload(fixed.getId(), true, "已复现修复")))));
        asUser(DEV_OWNER, () -> appService.confirm(taskId));

        // 复测确认只摄取报告；Bug 状态演化（FIXED/VERIFIED）明确不入（A5 §1）
        ArgumentCaptor<KnowledgeSpec> spec = ArgumentCaptor.forClass(KnowledgeSpec.class);
        verify(knowledgePort, times(1)).index(spec.capture());
        assertThat(spec.getValue().kind()).isEqualTo(ProjectKnowledgeAppService.KIND_TEST_REPORT);
    }

    @Test
    void given_index_failure_when_confirm_then_confirmed_anyway() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = submittedFirstRound(projectId, assignee, List.of());
        doThrow(new RuntimeException("向量库写失败")).when(knowledgePort).index(any());

        TaskDetailResponse detail = asUser(DEV_OWNER, () -> appService.confirm(taskId));

        // 摄取失败降级不炸（A5 §1）：确认与修复链触发照常
        assertThat(detail.task().status()).isEqualByComparingTo(TaskStatus.CONFIRMED);
    }

    // ---------- 确认触发修复链（A4 §4 触发①②，#27） ----------

    @Test
    void given_bugs_confirmed_when_confirm_then_fix_chain_triggered() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = submittedFirstRound(projectId, assignee, List.of(
                new SubmitTaskCommand.BugPayload("登录 500", "提交后 500", "1. 打开登录页",
                        BugSeverity.CRITICAL)));

        asUser(DEV_OWNER, () -> appService.confirm(taskId));

        // 首轮确认入库 OPEN → 事务提交后自动触发修复链：一 run 派出、Bug 行挂 in-flight 标记
        ArgumentCaptor<AgentTaskDispatchCommand> command =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        verify(agentTaskAppService, times(1)).dispatch(anyString(), command.capture(),
                any(), any());
        assertThat(command.getValue().prompt()).contains("登录 500");
        assertThat(bugRows(projectId).get(0).get("fix_run_id")).isNotNull();
    }

    @Test
    void given_empty_bugs_confirmed_when_confirm_then_no_fix_dispatch() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = submittedFirstRound(projectId, assignee, List.of());

        asUser(DEV_OWNER, () -> appService.confirm(taskId));

        // 空 Bug 清单（测试全过）：无修复派发
        verify(agentTaskAppService, never()).dispatch(anyString(), any(), any(), any());
    }

    @Test
    void given_retest_fail_when_confirm_then_returned_bug_redispatched() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = createdTaskOf(projectId, assignee);
        Bug fixed = bugRepository.save(Bug.fixedOf(projectId, taskId, "登录 500",
                "run-9", "已修复登录拦截"));

        asUser(assignee, () -> appService.start(taskId));
        asUser(assignee, () -> appService.submit(taskId, new SubmitTaskCommand(
                "复测报告", null,
                List.of(new SubmitTaskCommand.RetestResultPayload(fixed.getId(), false, "仍复现")))));
        asUser(DEV_OWNER, () -> appService.confirm(taskId));

        // 复测退回 OPEN（修复字段清空）→ 自动再派发修复（A4 §4 触发②）
        ArgumentCaptor<AgentTaskDispatchCommand> command =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        verify(agentTaskAppService, times(1)).dispatch(anyString(), command.capture(),
                any(), any());
        assertThat(command.getValue().prompt()).contains("登录 500");
        Map<String, Object> row = bugRow(fixed.getId());
        assertThat(row.get("status")).isEqualTo(1); // 退回 OPEN
        assertThat(row.get("fix_run_id")).isNotNull(); // 新修复 run 已挂
        assertThat(row.get("fix_note")).isNull(); // 旧结论已作废
    }

    // ---------- 转任务来源（A1 §3.1 第 1 步，#27） ----------

    @Test
    void given_deferred_task_when_confirmed_then_completed_carries_wait_id() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");

        TaskResponse created = asUser(DEV_OWNER, () -> appService.createFromWait(projectId,
                "wait-abc", new CreateTaskCommand("调研框架", "去调研后端选型", assignee)));
        Long taskId = Long.parseLong(created.taskId());

        // waitId 不透明引用随任务落库（REST 建任务不带——waitId 列程序化专用）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT wait_id FROM tsk_tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("wait-abc");
        // 非 owner 经端口建任务同守卫：TASK_009
        assertThatThrownBy(() -> asUser(assignee, () -> appService.createFromWait(projectId,
                "wait-abc", new CreateTaskCommand("越权", "内容", assignee))))
                .hasMessageContaining(TaskMessage.TASK_NOT_OWNER.message());

        asUser(assignee, () -> appService.start(taskId));
        asUser(assignee, () -> appService.submit(taskId,
                new SubmitTaskCommand("结论：用 X", List.of(), null)));
        asUser(DEV_OWNER, () -> appService.confirm(taskId));

        // TaskCompleted 带 waitId——project 侧回填续跑的锚点（A1 §3.1 第 3 步）
        assertThat(completedRecorder.committed).hasSize(1);
        assertThat(completedRecorder.committed.get(0).waitId()).isEqualTo("wait-abc");
        assertThat(completedRecorder.committed.get(0).summary()).contains("结论：用 X");
    }

    // ---------- bogus 手工关闭（A4 §4，#27） ----------

    @Test
    void given_bogus_bug_when_close_then_verified_with_reason() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = createdTaskOf(projectId, assignee);
        Bug bogus = bugRepository.save(Bug.openOf(projectId, taskId, "未重现", null, null,
                BugSeverity.MINOR));
        Bug fixedBogus = bugRepository.save(Bug.fixedOf(projectId, taskId, "样式错位",
                "run-9", "已修"));

        asUser(DEV_OWNER, () -> appService.closeBug(projectId.toString(), bogus.getId(),
                "需求如此，非缺陷"));
        asUser(DEV_OWNER, () -> appService.closeBug(projectId.toString(), fixedBogus.getId(),
                "未重现，本地无法复现"));

        // OPEN/FIXED 均可关 → VERIFIED + closed_reason（唯一关闭态的带理由别名动作）
        assertThat(bugRow(bogus.getId())).containsEntry("status", 3)
                .containsEntry("closed_reason", "需求如此，非缺陷");
        assertThat(bugRow(fixedBogus.getId()).get("status")).isEqualTo(3);
        assertThat(openBugQueryPort.hasOpenBugs(projectId)).isFalse(); // G3 谓词不变

        // VERIFIED 终态再关 TASK_002；reason 空 TASK_010；非归属 TASK_009
        assertThatThrownBy(() -> asUser(DEV_OWNER, () -> appService.closeBug(
                projectId.toString(), bogus.getId(), "再关")))
                .isInstanceOfSatisfying(CartisanException.class, e ->
                        assertThat(e.getCodeMessage().code()).isEqualTo("TASK_002"));
        assertThatThrownBy(() -> asUser(DEV_OWNER, () -> appService.closeBug(
                projectId.toString(), fixedBogus.getId(), " ")))
                .hasMessageContaining(TaskMessage.BUG_CLOSE_REASON_REQUIRED.message());
        assertThatThrownBy(() -> asUser(assignee, () -> appService.closeBug(
                projectId.toString(), fixedBogus.getId(), "越权")))
                .hasMessageContaining(TaskMessage.TASK_NOT_OWNER.message());
    }

    @Test
    void given_foreign_bug_when_close_then_task_005() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long otherProject = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = createdTaskOf(projectId, assignee);
        Long foreignBug = bugRepository.save(Bug.openOf(otherProject, taskId, "别家 Bug",
                null, null, BugSeverity.MINOR)).getId();

        assertThatThrownBy(() -> asUser(DEV_OWNER, () -> appService.closeBug(
                projectId.toString(), foreignBug, "越界")))
                .hasMessageContaining(TaskMessage.BUG_NOT_FOUND.message());
    }

    // ---------- dev 动作 owner 守卫（A4 §6 视角列） ----------

    @Test
    void given_assignee_when_create_or_confirm_then_task_009() throws Exception {
        // assignee（OPC 侧）不能建任务 / 确认 / 驳回 / 取消——防自确认闭环
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");

        assertThatThrownBy(() -> asUser(assignee, () -> appService.create(projectId.toString(),
                new CreateTaskCommand("越权建", "内容", assignee))))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(TaskMessage.TASK_NOT_OWNER.message());

        Long taskId = submittedFirstRound(projectId, assignee, List.of());
        assertThatThrownBy(() -> asUser(assignee, () -> appService.confirm(taskId)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(TaskMessage.TASK_NOT_OWNER.message());
        assertThatThrownBy(() -> asUser(assignee, () -> appService.reject(taskId, "理由")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(TaskMessage.TASK_NOT_OWNER.message());
        assertThatThrownBy(() -> asUser(assignee, () -> appService.cancel(taskId)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(TaskMessage.TASK_NOT_OWNER.message());

        // 状态未动、零事件（守卫先于状态机与事务）
        assertThat(taskStatus(taskId)).isEqualTo(3);
        assertThat(completedRecorder.committed).isEmpty();
    }

    // ---------- 守卫与非法迁移 ----------

    @Test
    void given_non_assignee_when_start_or_submit_then_task_004() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = createdTaskOf(projectId, assignee);

        assertThatThrownBy(() -> asUser(DEV_OWNER, () -> appService.start(taskId)))
                .isInstanceOf(CartisanException.class)
                .hasMessageContaining(TaskMessage.NOT_ASSIGNEE.message());
        assertThatThrownBy(() -> asUser(DEV_OWNER, () -> appService.submit(taskId,
                new SubmitTaskCommand("报告", List.of(), null))))
                .isInstanceOf(CartisanException.class)
                .hasMessageContaining(TaskMessage.NOT_ASSIGNEE.message());
    }

    @Test
    void given_malformed_payload_when_submit_then_task_006() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = createdTaskOf(projectId, assignee);
        asUser(assignee, () -> appService.start(taskId));

        // 同给 / 同缺 / 缺报告
        assertThatThrownBy(() -> asUser(assignee, () -> appService.submit(taskId,
                new SubmitTaskCommand("报告", List.of(), List.of()))))
                .hasMessageContaining(TaskMessage.SUBMIT_PAYLOAD_INVALID.message());
        assertThatThrownBy(() -> asUser(assignee, () -> appService.submit(taskId,
                new SubmitTaskCommand("报告", null, null))))
                .hasMessageContaining(TaskMessage.SUBMIT_PAYLOAD_INVALID.message());
        assertThatThrownBy(() -> asUser(assignee, () -> appService.submit(taskId,
                new SubmitTaskCommand(" ", List.of(), null))))
                .hasMessageContaining(TaskMessage.SUBMIT_PAYLOAD_INVALID.message());
    }

    @Test
    void given_foreign_bug_when_retest_submit_then_task_005() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long otherProject = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = createdTaskOf(projectId, assignee);
        Bug foreignBug = bugRepository.save(Bug.openOf(otherProject, taskId, "别家 Bug",
                null, null, BugSeverity.MINOR));
        asUser(assignee, () -> appService.start(taskId));

        assertThatThrownBy(() -> asUser(assignee, () -> appService.submit(taskId,
                new SubmitTaskCommand("复测", null,
                        List.of(new SubmitTaskCommand.RetestResultPayload(foreignBug.getId(),
                                true, null))))))
                .hasMessageContaining(TaskMessage.BUG_NOT_FOUND.message());
    }

    @Test
    void given_reject_then_resubmit_clears_rejection() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");
        Long taskId = createdTaskOf(projectId, assignee);
        asUser(assignee, () -> appService.start(taskId));
        asUser(assignee, () -> appService.submit(taskId,
                new SubmitTaskCommand("首轮", List.of(), null)));

        TaskResponse rejected = asUser(DEV_OWNER, () -> appService.reject(taskId, "缺登录用例"));
        assertThat(rejected.status()).isEqualByComparingTo(TaskStatus.IN_PROGRESS);
        assertThat(rejected.rejectReason()).isEqualTo("缺登录用例");
        assertThat(rejected.rejectedAt()).isNotNull();
        assertThatThrownBy(() -> asUser(DEV_OWNER, () -> appService.reject(taskId, " "))) // reason 必填
                .hasMessageContaining(TaskMessage.REJECT_REASON_REQUIRED.message());

        // 重新提交：驳回字段清空、离开 TASK_REJECTED 判定
        TaskResponse resubmitted = asUser(assignee, () -> appService.submit(taskId,
                new SubmitTaskCommand("补跑登录", List.of(), null)));
        assertThat(resubmitted.status()).isEqualByComparingTo(TaskStatus.SUBMITTED);
        assertThat(resubmitted.rejectReason()).isNull();
        assertThat(resubmitted.rejectedAt()).isNull();

        // 驳回路径零 Bug 落库（tsk_bugs 确认前不动）
        assertThat(bugRows(projectId)).isEmpty();
        assertThat(completedRecorder.committed).isEmpty(); // 驳回/取消不发
    }

    @Test
    void given_submitted_when_cancel_then_task_002_but_published_cancellable() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");

        // 已发布 → 可取消
        Long cancellable = createdTaskOf(projectId, assignee);
        assertThat(asUser(DEV_OWNER, () -> appService.cancel(cancellable)).status())
                .isEqualByComparingTo(TaskStatus.CANCELLED);

        // 已提交 → 不能取消只能驳回（docs/11 原样）
        Long submitted = submittedFirstRound(projectId, assignee, List.of());
        assertThatThrownBy(() -> asUser(DEV_OWNER, () -> appService.cancel(submitted)))
                .isInstanceOfSatisfying(CartisanException.class, e -> {
                    assertThat(e.getCodeMessage().code()).isEqualTo("TASK_002");
                    assertThat(e.getMessage()).contains("SUBMITTED").contains("CANCELLED");
                });
    }

    @Test
    void given_illegal_jumps_when_operate_then_task_002() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST, DEV_OWNER);
        Long assignee = persistedAccount("sub-opc", "外包测试");

        Long fresh = createdTaskOf(projectId, assignee);
        assertThatThrownBy(() -> asUser(DEV_OWNER, () -> appService.confirm(fresh))) // 未提交直接确认
                .isInstanceOf(CartisanException.class)
                .hasMessageContaining("PUBLISHED");

        Long started = createdTaskOf(projectId, assignee);
        asUser(assignee, () -> appService.start(started));
        assertThatThrownBy(() -> asUser(assignee, () -> appService.start(started))) // 重复 start
                .isInstanceOf(CartisanException.class)
                .hasMessageContaining("IN_PROGRESS");
    }

    @Test
    void given_missing_task_when_operate_then_task_007() {
        assertThatThrownBy(() -> appService.start(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(TaskMessage.TASK_NOT_FOUND.message());
        verifyNoInteractions(notificationAppService);
    }

    // ---------- 测试数据 ----------

    private Long persistedAccount(String externalId, String displayName) {
        return accountRepository.save(Account.register(externalId, displayName)).getId();
    }

    private Long persistedProjectWithStage(String stage, Long ownerAccountId) {
        Project project = projectRepository.save(Project.create("任务测试", ProjectType.WEBSITE,
                "opencode", 9301L, ownerAccountId));
        iterationRepository.save(Iteration.open(project.getId(), Iteration.FIRST_SEQ, stage));
        return project.getId();
    }

    private Long persistedClosedProject(Long ownerAccountId) {
        Project project = projectRepository.save(Project.create("收口项目", ProjectType.WEBSITE,
                "opencode", 9302L, ownerAccountId));
        Iteration iteration = Iteration.open(project.getId(), Iteration.FIRST_SEQ,
                ProjectMainChain.STAGE_ACCEPTANCE);
        iteration.close(ProjectMainChain.STAGE_CLOSED);
        iterationRepository.save(iteration);
        return project.getId();
    }

    private Long createdTaskOf(Long projectId, Long assignee) throws Exception {
        return Long.parseLong(asUser(DEV_OWNER, () -> appService
                .create(projectId.toString(),
                        new CreateTaskCommand("回归测试", "全量回归", assignee))
                .taskId()));
    }

    /** 建任务 → start → 首轮 submit（bugs 清单可空）的便捷前奏。 */
    private Long submittedFirstRound(Long projectId, Long assignee,
                                     List<SubmitTaskCommand.BugPayload> bugs) throws Exception {
        Long taskId = createdTaskOf(projectId, assignee);
        asUser(assignee, () -> appService.start(taskId));
        asUser(assignee, () -> appService.submit(taskId,
                new SubmitTaskCommand(bugs.isEmpty() ? "测试全过" : "首轮报告", bugs, null)));
        return taskId;
    }

    private Iteration openIteration(Long projectId) {
        return iterationRepository
                .findByProjectIdAndStatus(projectId, IterationStatus.OPEN).orElseThrow();
    }

    private int taskStatus(Long taskId) {
        return jdbcTemplate.queryForObject("SELECT status FROM tsk_tasks WHERE id = ?",
                Integer.class, taskId);
    }

    private long taskCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tsk_tasks", Long.class);
    }

    private List<Map<String, Object>> bugRows(Long projectId) {
        return jdbcTemplate.queryForList("SELECT * FROM tsk_bugs WHERE project_id = ?",
                projectId);
    }

    private Map<String, Object> bugRow(Long bugId) {
        return jdbcTemplate.queryForMap("SELECT * FROM tsk_bugs WHERE id = ?", bugId);
    }

    private <T> T asUser(Long userId, RequestContextCall<T> call) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, userId, "task-test", null, null),
                call::get);
    }

    @FunctionalInterface
    private interface RequestContextCall<T> {
        T get();
    }

    // ---------- TaskCompleted 记录器（AFTER_COMMIT 送达断言） ----------

    @TestConfiguration
    static class EventRecorderConfig {

        @Bean
        TaskCompletedRecorder taskCompletedRecorder() {
            return new TaskCompletedRecorder();
        }
    }

    static class TaskCompletedRecorder {

        final List<TaskCompleted> committed = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(TaskCompleted event) {
            committed.add(event);
        }
    }
}
