package com.aieducenter.aiplatform.business.task.application;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.business.task.application.dto.command.CreateTaskCommand;
import com.aieducenter.aiplatform.business.task.application.dto.command.SubmitTaskCommand;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskCardResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskDetailResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskTodoSource;
import com.aieducenter.aiplatform.business.task.domain.aggregate.Bug;
import com.aieducenter.aiplatform.business.task.domain.enums.BugSeverity;
import com.aieducenter.aiplatform.business.task.domain.enums.TaskStatus;
import com.aieducenter.aiplatform.business.task.domain.error.TaskMessage;
import com.aieducenter.aiplatform.business.task.domain.repository.BugRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务读侧（票 #26，A4 §6/§7）：opc 指派清单（assignee=me + 最小项目上下文）
 * / 详情谓词（assignee ∨ owner，第三方 403 TASK_004）/ Bug 面板 / workbench
 * 四型待办源（RETEST_READY 三谓词：FIXED ∧ 无进行中任务 ∧ 无 in-flight 修复）。
 */
@SpringBootTest
class TaskQueryAppServiceTest {

    private static final Long OWNER = 7800L;

    @Autowired
    private TaskLifecycleAppService lifecycleAppService;

    @Autowired
    private TaskQueryAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private IterationRepository iterationRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BugRepository bugRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tsk_bugs");
        jdbcTemplate.update("DELETE FROM tsk_tasks");
        jdbcTemplate.update("DELETE FROM prj_iterations");
        jdbcTemplate.update("DELETE FROM prj_projects");
        jdbcTemplate.update("DELETE FROM idn_accounts");
    }

    // ---------- opc 指派清单 ----------

    @Test
    void given_tasks_across_projects_when_my_tasks_then_only_mine_with_project_context()
            throws Exception {
        Long account = persistedAccount("sub-opc", "外包测试");
        Long p1 = persistedProject("官网项目");
        Long p2 = persistedProject("商城项目");
        Long taskId1 = createdTask(p1, account);
        Long taskId2 = createdTask(p2, account);
        createdTask(p1, persistedAccount("sub-other", "别人")); // 他人的任务

        List<TaskCardResponse> cards = asUser(account, appService::myTasks);

        assertThat(cards).hasSize(2);
        assertThat(cards).extracting(TaskCardResponse::taskId)
                .containsExactly(taskId2.toString(), taskId1.toString()); // 新者在前
        // 最小项目上下文：项目名 + 预览地址（工作区记录未建 → 预览置 null 不炸）
        assertThat(cards.get(0).project().name()).isEqualTo("商城项目");
        assertThat(cards.get(0).project().previewUrl()).isNull();
        assertThat(cards.get(0).status()).isEqualByComparingTo(TaskStatus.PUBLISHED);
        assertThat(cards.get(0).statusName()).isEqualTo("已发布");
    }

    // ---------- 详情谓词（assignee ∨ owner） ----------

    @Test
    void given_task_when_detail_then_assignee_and_owner_pass_outsider_403() throws Exception {
        Long account = persistedAccount("sub-opc", "外包测试");
        Long outsider = persistedAccount("sub-out", "路人");
        Long projectId = persistedProject("谓词项目");
        Long taskId = createdTask(projectId, account);

        TaskDetailResponse byAssignee = asUser(account, () -> appService.detail(taskId));
        assertThat(byAssignee.task().taskId()).isEqualTo(taskId.toString());
        assertThat(byAssignee.task().assigneeName()).isEqualTo("外包测试");

        TaskDetailResponse byOwner = asUser(OWNER, () -> appService.detail(taskId)); // 项目 owner = dev 侧
        assertThat(byOwner.task().taskId()).isEqualTo(taskId.toString());

        assertThatThrownBy(() -> asUser(outsider, () -> appService.detail(taskId)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(TaskMessage.NOT_ASSIGNEE.message());
    }

    @Test
    void given_task_and_bugs_when_detail_then_bugs_carried_for_retest_form() throws Exception {
        Long account = persistedAccount("sub-opc", "外包测试");
        Long projectId = persistedProject("复测项目");
        Long taskId = createdTask(projectId, account);
        bugRepository.save(Bug.openOf(projectId, taskId, "登录 500", "描述", "步骤",
                BugSeverity.CRITICAL));

        TaskDetailResponse detail = asUser(account, () -> appService.detail(taskId));

        assertThat(detail.bugs()).hasSize(1);
        assertThat(detail.bugs().get(0).title()).isEqualTo("登录 500");
        assertThat(detail.bugs().get(0).severityName()).isEqualTo("严重");
        assertThat(detail.bugs().get(0).statusName()).isEqualTo("待修复");
    }

    // ---------- 四型待办源（A4 §7 澄清表） ----------

    @Test
    void given_submitted_task_when_todo_sources_then_submitted_only() throws Exception {
        Long account = persistedAccount("sub-opc", "外包测试");
        Long projectId = persistedProject("待办项目");
        Long submittedTask = submittedTask(projectId, account);
        createdTask(projectId, account); // 已发布不进 dev 型

        assertThat(appService.submittedTodoSources())
                .extracting(TaskTodoSource::taskId)
                .containsExactly(submittedTask.toString());

        // opc 源：NEW_TASK（assignee=me ∧ PUBLISHED）
        assertThat(appService.publishedTodoSources(account)).hasSize(1);
        assertThat(appService.publishedTodoSources(persistedAccount("sub-x", "无任务")))
                .isEmpty();
    }

    @Test
    void given_rejected_task_when_rejected_sources_then_only_rejected_in_progress()
            throws Exception {
        Long account = persistedAccount("sub-opc", "外包测试");
        Long projectId = persistedProject("驳回项目");

        // 提交后被驳回 → IN_PROGRESS ∧ rejected_at 非空
        Long taskId = createdTask(projectId, account);
        asUser(account, () -> lifecycleAppService.start(taskId));
        asUser(account, () -> lifecycleAppService.submit(taskId,
                new SubmitTaskCommand("报告", List.of(), null)));
        asUser(OWNER, () -> lifecycleAppService.reject(taskId, "补登录用例"));

        List<TaskTodoSource> rejected = appService.rejectedTodoSources(account);
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).taskId()).isEqualTo(taskId.toString());
        assertThat(rejected.get(0).since()).isNotNull();

        // 重新提交后离开（rejected_at 清空）
        asUser(account, () -> lifecycleAppService.submit(taskId,
                new SubmitTaskCommand("补跑", List.of(), null)));
        assertThat(appService.rejectedTodoSources(account)).isEmpty();
    }

    @Test
    void given_fixed_bugs_when_retest_ready_then_three_predicates_rule() throws Exception {
        Long account = persistedAccount("sub-opc", "外包测试");
        Long readyProject = persistedProject("就绪项目");
        Long busyProject = persistedProject("复测中项目");
        Long inflightProject = persistedProject("修复中项目");
        // Bug 溯源键须是真实测试任务（FK：tsk_bugs.source_task_id → tsk_tasks）
        Long readyTask = createdTask(readyProject, persistedAccount("sub-a", "甲"));
        Long busyTask = createdTask(busyProject, account); // 谓词②的进行中任务
        Long inflightTask = createdTask(inflightProject, persistedAccount("sub-b", "乙"));
        Long openTask = createdTask(inflightProject, persistedAccount("sub-d", "丁"));
        // 就绪/修复中项目的任务只是 Bug 的溯源载体（FK 需要），取消到终态让
        // 「无进行中任务」半边成立；busyProject 的任务保持 PUBLISHED（谓词②本体）
        asUser(OWNER, () -> lifecycleAppService.cancel(readyTask));
        asUser(OWNER, () -> lifecycleAppService.cancel(inflightTask));
        asUser(OWNER, () -> lifecycleAppService.cancel(openTask));

        // 就绪：FIXED Bug ∧ 无进行中任务 ∧ 无 in-flight
        bugRepository.save(Bug.fixedOf(readyProject, readyTask, "Bug A", "run-1", "已修"));
        assertThat(appService.retestReadyProjects())
                .extracting(TaskTodoSource::projectId)
                .containsExactly(readyProject.toString());

        // 谓词②：存在进行中测试任务（PUBLISHED）→ 不亮
        bugRepository.save(Bug.fixedOf(busyProject, busyTask, "Bug B", "run-2", "已修"));
        // 谓词③：in-flight 修复（OPEN ∧ fix_run_id 非空——#27 链运行中）→ 不亮
        Bug inflight = bugRepository.save(Bug.openOf(inflightProject, openTask, "Bug C",
                null, null, BugSeverity.MAJOR));
        jdbcTemplate.update("UPDATE tsk_bugs SET fix_run_id = 'run-3' WHERE id = ?",
                inflight.getId());
        bugRepository.save(Bug.fixedOf(inflightProject, inflightTask, "Bug D", "run-4", "已修"));

        List<TaskTodoSource> ready = appService.retestReadyProjects();
        assertThat(ready).extracting(TaskTodoSource::projectId)
                .containsExactly(readyProject.toString())
                .doesNotContain(busyProject.toString(), inflightProject.toString());
    }

    // ---------- Bug 面板 ----------

    @Test
    void given_project_bugs_when_bugs_then_newest_first_with_full_fields() throws Exception {
        Long projectId = persistedProject("面板项目");
        Long sourceTask = createdTask(projectId, persistedAccount("sub-c", "丙"));
        bugRepository.save(Bug.openOf(projectId, sourceTask, "早 Bug", null, null,
                BugSeverity.MINOR));
        bugRepository.save(Bug.fixedOf(projectId, sourceTask, "晚 Bug", "run-9", "已修复"));

        List<Map<String, Object>> bugs = appService.bugs(projectId.toString()).stream()
                .map(bug -> Map.<String, Object>of("title", bug.title(),
                        "status", bug.status(), "fixRunId", bug.fixRunId() == null ? "" : bug.fixRunId()))
                .toList();

        assertThat(bugs).extracting(map -> map.get("title"))
                .containsExactly("晚 Bug", "早 Bug"); // 新→旧
        assertThat(appService.bugs(projectId.toString())).element(0)
                .satisfies(bug -> {
                    assertThat(bug.fixRunId()).isEqualTo("run-9");
                    assertThat(bug.fixNote()).isEqualTo("已修复");
                });
    }

    // ---------- 测试数据 ----------

    private Long persistedAccount(String externalId, String displayName) {
        return accountRepository.save(Account.register(externalId, displayName)).getId();
    }

    private Long persistedProject(String name) {
        Project project = projectRepository.save(Project.create(name, ProjectType.WEBSITE,
                "opencode", 9303L, OWNER));
        iterationRepository.save(Iteration.open(project.getId(), Iteration.FIRST_SEQ,
                ProjectMainChain.STAGE_TEST));
        return project.getId();
    }

    private Long createdTask(Long projectId, Long assignee) throws Exception {
        return Long.parseLong(asUser(OWNER, () -> lifecycleAppService
                .create(projectId.toString(),
                        new CreateTaskCommand("回归测试", "全量回归", assignee))
                .taskId()));
    }

    private Long submittedTask(Long projectId, Long assignee) throws Exception {
        Long taskId = createdTask(projectId, assignee);
        asUser(assignee, () -> lifecycleAppService.start(taskId));
        asUser(assignee, () -> lifecycleAppService.submit(taskId,
                new SubmitTaskCommand("报告", List.of(), null)));
        return taskId;
    }

    private <T> T asUser(Long userId, RequestContextCall<T> call) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, userId, "task-query-test", null, null),
                call::get);
    }

    @FunctionalInterface
    private interface RequestContextCall<T> {
        T get();
    }
}
