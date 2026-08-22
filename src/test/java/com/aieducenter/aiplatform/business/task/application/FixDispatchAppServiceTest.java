package com.aieducenter.aiplatform.business.task.application;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.BaseCodeMessage;

import com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext;
import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentTaskAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentTaskDispatchCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentTaskResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.business.task.domain.aggregate.Bug;
import com.aieducenter.aiplatform.business.task.domain.aggregate.Task;
import com.aieducenter.aiplatform.business.task.domain.enums.BugSeverity;
import com.aieducenter.aiplatform.business.task.domain.enums.TaskType;
import com.aieducenter.aiplatform.business.task.domain.error.TaskMessage;
import com.aieducenter.aiplatform.business.task.domain.repository.BugRepository;
import com.aieducenter.aiplatform.business.task.domain.repository.TaskRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 修复编排链（票 #27 验收主面，A4 §4）：dispatchFixes 幂等（只派 OPEN ∧ 无
 * run 引用；in-flight = OPEN ∧ 引用非空即空转）；串行链 sink 收终态——finish
 * → FIXED 乐观翻转 + fix_run_id/fix_note → 下一条；error/timeout → 留 OPEN
 * 清引用回池（失败不阻塞链，链内不重试失败 Bug）；同步拒绝/异常同失败收口；
 * 经 project 端口全继承片5 编排（DEV preset、dims role=FIX、阶段计数、期 CLOSED
 * 跳过）；重启恢复置 NULL 回池——#36 后只回收宽限外的陈旧标记（跨实例在飞标记
 * 存活、陈旧死链由链前进步回收、迟到终态守卫拒+无害冗余重派不回退）。
 */
@SpringBootTest
class FixDispatchAppServiceTest {

    private static final Long DEV_OWNER = 8800L;

    @Autowired
    private FixDispatchAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private IterationRepository iterationRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private BugRepository bugRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 底座编排入口 mock：链对终态的裁决用捕获的 observer 模拟引擎回调。 */
    @MockitoBean
    private AgentTaskAppService agentTaskAppService;

    /** 知识端口 mock（A5 注入缝在 dispatchFixRun 前；空命中 = 不注入，链行为不涉知识）。 */
    @MockitoBean
    private KnowledgePort knowledgePort;

    @MockitoBean
    private AgentStreamAppService streamAppService;

    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tsk_bugs");
        jdbcTemplate.update("DELETE FROM tsk_tasks");
        jdbcTemplate.update("DELETE FROM prj_iterations");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    // ---------- 循环全貌：finish 链式翻 FIXED（验收①） ----------

    @Test
    void given_open_bugs_when_dispatch_then_serial_chain_fixes_each() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST);
        Long bugA = persistedBug(projectId, "登录 500");
        Long bugB = persistedBug(projectId, "样式错位");
        stubAccepted();

        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));

        // 串行：先派最旧的 bugA（逐 Bug 一 run 一新会话），finish 前不派下一条
        assertThat(dispatchCount()).isEqualTo(1);
        assertThat(promptOf(0)).contains("登录 500");

        observerOf(0).accept(textEvent("已修复登录拦截"));
        observerOf(0).accept(finishEvent());

        assertThat(dispatchCount()).isEqualTo(2);
        assertThat(promptOf(1)).contains("样式错位");

        observerOf(1).accept(textEvent("已调整样式"));
        observerOf(1).accept(finishEvent());

        // 两条 Bug：FIXED + fix_run_id/fix_note 落库；无第三次派发（链完）
        assertThat(dispatchCount()).isEqualTo(2);
        Map<String, Object> rowA = bugRow(bugA);
        Map<String, Object> rowB = bugRow(bugB);
        assertThat(rowA.get("status")).isEqualTo(2);
        assertThat(rowA.get("fix_note")).isEqualTo("已修复登录拦截");
        assertThat(rowA.get("fix_run_id")).isNotNull();
        assertThat(rowB.get("status")).isEqualTo(2);
        assertThat(rowB.get("fix_note")).isEqualTo("已调整样式");

        // 阶段计数：修复 run 计入当前（测试）段——minTasks=1 由修复 run 凑上（A4 §5）
        assertThat(openIteration(projectId).getStageTaskCount()).isEqualTo(2);
    }

    @Test
    void given_fix_run_when_dispatched_then_dev_preset_fix_dims_new_session()
            throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST);
        persistedBug(projectId, "登录 500");
        stubAccepted();

        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));

        // DEV preset（systemPrompt/modelId）+ sessionId=null（一 run 一新会话）
        AgentTaskDispatchCommand command = commandOf(0);
        assertThat(command.systemPrompt()).isNotBlank();
        assertThat(command.modelId()).isNotBlank();
        assertThat(command.sessionId()).isNull();
        assertThat(command.prompt()).contains("【标题】登录 500")
                .contains("【复现步骤】1. 打开登录页")
                .contains("【严重档位】严重");
        // 计量 dims role=FIX 区分 + subject=projectId + projectId 流关联
        AgentRunContext context = contextOf(0);
        assertThat(context.usageContext().subject()).isEqualTo(projectId.toString());
        assertThat(context.usageContext().dims())
                .containsEntry("role", "FIX")
                .containsEntry("stage", ProjectMainChain.STAGE_TEST);
        assertThat(context.streamCorrelation())
                .containsEntry(AgentStreamAppService.PROJECT_FIELD, projectId.toString());
        // runId 与 Bug 行 in-flight 标记同值（先落标记再下发）
        assertThat(bugRows(projectId).get(0).get("fix_run_id"))
                .isEqualTo(context.runId());
    }

    // ---------- 失败不阻塞链（验收②） ----------

    @Test
    void given_error_terminal_when_dispatch_then_bug_stays_open_chain_continues()
            throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST);
        Long bugA = persistedBug(projectId, "登录 500");
        Long bugB = persistedBug(projectId, "样式错位");
        stubAccepted();

        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));
        observerOf(0).accept(errorEvent("任务超时"));

        // error/timeout：留 OPEN + fix_run_id 清 NULL 回池，链继续下一条
        Map<String, Object> rowA = bugRow(bugA);
        assertThat(rowA.get("status")).isEqualTo(1);
        assertThat(rowA.get("fix_run_id")).isNull();
        assertThat(dispatchCount()).isEqualTo(2);
        assertThat(promptOf(1)).contains("样式错位");

        observerOf(1).accept(finishEvent());
        assertThat(bugRow(bugB).get("status")).isEqualTo(2);
        // 链内不重派 bugA（回池待下次派发——手动端点/下一轮确认触发）
        assertThat(dispatchCount()).isEqualTo(2);
    }

    @Test
    void given_sync_rejection_or_dispatch_exception_when_dispatch_then_failure_path_continues()
            throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST);
        Long bugA = persistedBug(projectId, "登录 500");
        Long bugB = persistedBug(projectId, "样式错位");

        // 引擎未接受（accepted=false）：失败收口 → 下一条（两条都试过即链完）
        when(agentTaskAppService.dispatch(anyString(), any(), any(), any()))
                .thenReturn(new AgentTaskResponse("run-x", null, "opencode", false));
        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));
        assertThat(dispatchCount()).isEqualTo(2);
        assertThat(bugRow(bugA).get("fix_run_id")).isNull();
        assertThat(bugRow(bugA).get("status")).isEqualTo(1);

        // 引擎交互抛异常：同样失败收口（标记回 NULL、链不断、异常不外抛）
        when(agentTaskAppService.dispatch(anyString(), any(), any(), any()))
                .thenThrow(new ApplicationException(BaseCodeMessage.INTERNAL_SERVER_ERROR));
        assertThatCode(() -> asUser(DEV_OWNER,
                () -> appService.dispatchFixes(projectId.toString()))).doesNotThrowAnyException();
        assertThat(dispatchCount()).isEqualTo(4);
        assertThat(bugRow(bugB).get("fix_run_id")).isNull();
        assertThat(bugRow(bugB).get("status")).isEqualTo(1);
    }

    // ---------- 派发幂等（验收③） ----------

    @Test
    void given_new_eligible_bug_during_chain_when_finish_then_picked_up_next()
            throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST);
        Long bugA = persistedBug(projectId, "登录 500");
        stubAccepted();

        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));
        assertThat(dispatchCount()).isEqualTo(1);

        // 链飞行中（bugA in-flight）：新 Bug 入池（如复测退回）+ 触发点空转
        Long bugB = persistedBug(projectId, "样式错位");
        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));
        assertThat(dispatchCount()).isEqualTo(1); // in-flight 幂等门

        // bugA 收尾 → 下一条现查可派池，接上 bugB（触发点不丢 Bug，一步一查）
        observerOf(0).accept(finishEvent());
        assertThat(dispatchCount()).isEqualTo(2);
        assertThat(promptOf(1)).contains("样式错位");
        observerOf(1).accept(finishEvent());

        assertThat(bugRow(bugA).get("status")).isEqualTo(2);
        assertThat(bugRow(bugB).get("status")).isEqualTo(2);
    }

    @Test
    void given_in_flight_run_when_dispatch_again_then_no_second_dispatch() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST);
        persistedBug(projectId, "登录 500");
        persistedBug(projectId, "样式错位");
        stubAccepted();

        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));
        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString())); // 重复手动触发

        // in-flight 判定（OPEN ∧ fix_run_id 非空）挡门：只有一条 run 在飞
        assertThat(dispatchCount()).isEqualTo(1);
        List<Map<String, Object>> rows = bugRows(projectId);
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(row -> row.get("fix_run_id") != null).count())
                .isEqualTo(1); // 第二条未被标记
    }

    @Test
    void given_no_dispatchable_bug_or_not_owner_when_dispatch_then_noop_or_task_009()
            throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST);

        // 无 OPEN Bug：空转
        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));
        verifyNoInteractions(agentTaskAppService);

        // 全 VERIFIED（bogus 手工关闭过）：同样空转（open = status ≠ VERIFIED 均关闭）
        Bug closed = bugRepository.save(Bug.openOf(projectId, sourceTaskOf(projectId),
                "已关闭", null, null, BugSeverity.MINOR));
        closed.closeManually("需求如此，非缺陷");
        bugRepository.save(closed);
        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));
        verifyNoInteractions(agentTaskAppService);

        // 非 owner（dev 动作守卫）：TASK_009
        assertThatThrownBy(() -> asUser(DEV_OWNER + 1,
                () -> appService.dispatchFixes(projectId.toString())))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(TaskMessage.TASK_NOT_OWNER.message());
    }

    // ---------- 重启恢复（验收④） ----------

    @Test
    void given_orphan_run_when_recover_then_null_and_redispatchable() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST);
        Long bugId = persistedBug(projectId, "登录 500");
        // 陈旧孤儿：标记落库时间超出宽限（引擎超时 30 分 + 松弛 5 分，#36）
        jdbcTemplate.update("UPDATE tsk_bugs SET fix_run_id = 'orphan-run', "
                + "updated_at = now() - interval '40 minutes' WHERE id = ?", bugId);
        stubAccepted();

        // 启动扫描：OPEN ∧ fix_run_id 非空 ∧ 宽限外 = 孤儿（链必已死），置 NULL 回池
        appService.recoverOrphanedRuns();
        assertThat(bugRow(bugId).get("fix_run_id")).isNull();

        // 回池后可再派发（重跑一次修复 = 无害冗余）
        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));
        assertThat(dispatchCount()).isEqualTo(1);
    }

    // ---------- 孤儿宽限与防御路径（#36：跨实例标记误清） ----------

    /**
     * #36 实录复现（冒烟 §7-②）：他实例共享库启动恢复不得清走在飞标记——链中
     * bugB 修复 run 在飞时兄弟实例启动（{@link #recoverOrphanedRuns()} 全库扫描），
     * 宽限内的新鲜标记必须存活：首次终态即翻 FIXED，每 Bug 只派发一次 fix run。
     */
    @Test
    void given_fresh_marker_when_sibling_boot_recovers_then_survives_and_single_dispatch()
            throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST);
        Long bugA = persistedBug(projectId, "登录 500");
        Long bugB = persistedBug(projectId, "样式错位");
        stubAccepted();

        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));
        observerOf(0).accept(finishEvent()); // bugA FIXED → bugB 派发（标记新鲜在飞）
        assertThat(dispatchCount()).isEqualTo(2);
        String runB = contextOf(1).runId();

        // 兄弟实例启动恢复（#36 根因：旧实现无差别清标记 → 守卫被拒 → 同一 Bug 修两次）
        appService.recoverOrphanedRuns();
        assertThat(bugRow(bugB).get("fix_run_id")).isEqualTo(runB); // 标记存活

        // 首次终态即翻 FIXED：无 WARN 拒、无重派（同链路每 Bug 只派一次）
        observerOf(1).accept(finishEvent());
        assertThat(dispatchCount()).isEqualTo(2);
        Map<String, Object> rowB = bugRow(bugB);
        assertThat(rowB.get("status")).isEqualTo(2);
        assertThat(rowB.get("fix_run_id")).isEqualTo(runB);
        assertThat(bugRow(bugA).get("status")).isEqualTo(2);
    }

    /**
     * #36 防御路径（冒烟实录的净效果形态，重跑=无害冗余不回退）：标记确已丢失
     * （外部清掉）时，迟到终态 markFixed 守卫拒——不误翻 FIXED，链重派一次收口。
     */
    @Test
    void given_marker_lost_externally_when_terminal_arrives_then_guard_rejects_and_redispatch()
            throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST);
        Long bugId = persistedBug(projectId, "登录 500");
        stubAccepted();

        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));
        assertThat(dispatchCount()).isEqualTo(1);

        // 模拟标记被外部清掉（#36 冒烟中为兄弟实例启动恢复所为）
        jdbcTemplate.update("UPDATE tsk_bugs SET fix_run_id = NULL WHERE id = ?", bugId);

        // 迟到终态：守卫拒（fixRunId 不匹配）→ 不误翻，链重派（无害冗余）
        observerOf(0).accept(finishEvent());
        assertThat(bugRow(bugId).get("status")).isEqualTo(1); // 未误翻 FIXED
        assertThat(dispatchCount()).isEqualTo(2);

        observerOf(1).accept(finishEvent());
        Map<String, Object> row = bugRow(bugId);
        assertThat(row.get("status")).isEqualTo(2);
        assertThat(row.get("fix_run_id")).isEqualTo(contextOf(1).runId());
    }

    /**
     * 真孤儿（陈旧标记）在触发点回收：宽限只护新鲜标记，重启后无人收终态的死链
     * 标记由链前进步回收续链——不留「卡 OPEN∧标记非空空转」的缝。
     */
    @Test
    void given_stale_marker_when_trigger_then_recycled_and_dispatched() throws Exception {
        Long projectId = persistedProjectWithStage(ProjectMainChain.STAGE_TEST);
        Long bugId = persistedBug(projectId, "登录 500");
        // 死链残留标记：落库时间超出宽限（引擎超时 30 分 + 松弛 5 分）
        jdbcTemplate.update("UPDATE tsk_bugs SET fix_run_id = 'dead-chain-run', "
                + "updated_at = now() - interval '40 minutes' WHERE id = ?", bugId);
        stubAccepted();

        asUser(DEV_OWNER, () -> appService.dispatchFixes(projectId.toString()));

        assertThat(dispatchCount()).isEqualTo(1); // 陈旧标记回收后照常派发
        assertThat(promptOf(0)).contains("登录 500");
        observerOf(0).accept(finishEvent());
        assertThat(bugRow(bugId).get("status")).isEqualTo(2);
    }

    // ---------- 期 CLOSED 后修复照常、不入期（验收⑦） ----------

    @Test
    void given_closed_iteration_when_dispatch_fix_then_runs_uncounted() throws Exception {
        Project project = projectRepository.save(Project.create("期后修复", ProjectType.WEBSITE,
                "opencode", 9501L, DEV_OWNER));
        Iteration iteration = iterationRepository.save(Iteration.open(project.getId(),
                Iteration.FIRST_SEQ, ProjectMainChain.STAGE_ACCEPTANCE));
        iteration.close(ProjectMainChain.STAGE_CLOSED);
        iterationRepository.save(iteration);
        persistedBug(project.getId(), "期后 Bug");
        stubAccepted();

        asUser(DEV_OWNER, () -> appService.dispatchFixes(project.getId().toString()));

        // 修复照常跑（工具正交），无 OPEN 期不计数、stage 记终态段名
        assertThat(dispatchCount()).isEqualTo(1);
        assertThat(contextOf(0).usageContext().dims())
                .containsEntry("stage", ProjectMainChain.STAGE_CLOSED);
        assertThat(iterationRepository.findByProjectIdAndStatus(project.getId(),
                IterationStatus.OPEN)).isEmpty();
    }

    // ---------- 测试数据与工具 ----------

    private void stubAccepted() {
        when(agentTaskAppService.dispatch(anyString(), any(), any(), any()))
                .thenAnswer(invocation -> new AgentTaskResponse(
                        invocation.getArgument(2, AgentRunContext.class).runId(),
                        "ses-fix", "opencode", true));
    }

    /** 第 index 次下发的 observer（模拟引擎异步终态回调的把手）。 */
    private Consumer<AgentEvent> observerOf(int index) {
        ArgumentCaptor<Consumer<AgentEvent>> captor = observerCaptor();
        return captor.getAllValues().get(index);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Consumer<AgentEvent>> observerCaptor() {
        ArgumentCaptor<Consumer<AgentEvent>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(agentTaskAppService, atLeastOnce()).dispatch(anyString(), any(), any(),
                captor.capture());
        return captor;
    }

    private int dispatchCount() {
        return dispatchedCommands().size();
    }

    private String promptOf(int index) {
        return commandOf(index).prompt();
    }

    private AgentTaskDispatchCommand commandOf(int index) {
        ArgumentCaptor<AgentTaskDispatchCommand> captor =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        verify(agentTaskAppService, atLeastOnce()).dispatch(anyString(), captor.capture(),
                any(), any());
        return captor.getAllValues().get(index);
    }

    private List<AgentTaskDispatchCommand> dispatchedCommands() {
        ArgumentCaptor<AgentTaskDispatchCommand> captor =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        verify(agentTaskAppService, atLeastOnce()).dispatch(anyString(), captor.capture(),
                any(), any());
        return captor.getAllValues();
    }

    private AgentRunContext contextOf(int index) {
        ArgumentCaptor<AgentRunContext> captor = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(agentTaskAppService, atLeastOnce()).dispatch(anyString(), any(), captor.capture(),
                any());
        return captor.getAllValues().get(index);
    }

    /** 引擎透传文本 part（两引擎同构：type=text，data.text=消息）。 */
    private static AgentEvent textEvent(String text) {
        return new AgentEvent("text", Map.of("runId", "run-x",
                "data", Map.of("type", "text", "text", text)));
    }

    private static AgentEvent finishEvent() {
        return new AgentEvent(AgentEventTypes.TASK_FINISH,
                Map.of("runId", "run-x", "finish", "end"));
    }

    private static AgentEvent errorEvent(String message) {
        return new AgentEvent(AgentEventTypes.ERROR, Map.of("runId", "run-x",
                "message", message));
    }

    private Long persistedProjectWithStage(String stage) {
        Project project = projectRepository.save(Project.create("修复链测试", ProjectType.WEBSITE,
                "opencode", 9400L + System.nanoTime() % 1000, DEV_OWNER));
        iterationRepository.save(Iteration.open(project.getId(), Iteration.FIRST_SEQ, stage));
        return project.getId();
    }

    private Long sourceTaskOf(Long projectId) {
        return taskRepository.save(Task.publish(projectId, TaskType.TEST, "来源任务",
                "测试任务", DEV_OWNER, null)).getId();
    }

    private Long persistedBug(Long projectId, String title) {
        return bugRepository.save(Bug.openOf(projectId, sourceTaskOf(projectId), title,
                "描述：" + title, "1. 打开登录页", BugSeverity.CRITICAL)).getId();
    }

    private Iteration openIteration(Long projectId) {
        return iterationRepository
                .findByProjectIdAndStatus(projectId, IterationStatus.OPEN).orElseThrow();
    }

    private List<Map<String, Object>> bugRows(Long projectId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM tsk_bugs WHERE project_id = ? ORDER BY created_at, id", projectId);
    }

    private Map<String, Object> bugRow(Long bugId) {
        return jdbcTemplate.queryForMap("SELECT * FROM tsk_bugs WHERE id = ?", bugId);
    }

    private <T> T asUser(Long userId, RequestContextCall<T> call) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, userId, "fix-test", null, null),
                call::get);
    }

    private void asUser(Long userId, RequestContextRunnable call) throws Exception {
        RequestContext.runFor(
                new RequestContext(null, null, null, null, userId, "fix-test", null, null),
                () -> {
                    call.run();
                    return null;
                });
    }

    @FunctionalInterface
    private interface RequestContextCall<T> {
        T get();
    }

    @FunctionalInterface
    private interface RequestContextRunnable {
        void run();
    }
}
