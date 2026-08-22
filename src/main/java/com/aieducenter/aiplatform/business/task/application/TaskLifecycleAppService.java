package com.aieducenter.aiplatform.business.task.application;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.event.ApplicationEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.business.identity.application.AccountAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectAgentTaskAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectKnowledgeAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.task.application.dto.command.CreateTaskCommand;
import com.aieducenter.aiplatform.business.task.application.dto.command.SubmitTaskCommand;
import com.aieducenter.aiplatform.business.task.application.dto.response.BugResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskDetailResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskResponse;
import com.aieducenter.aiplatform.business.task.application.event.TaskCompleted;
import com.aieducenter.aiplatform.business.task.domain.aggregate.Bug;
import com.aieducenter.aiplatform.business.task.domain.aggregate.Task;
import com.aieducenter.aiplatform.business.task.domain.enums.TaskType;
import com.aieducenter.aiplatform.business.task.domain.error.TaskMessage;
import com.aieducenter.aiplatform.business.task.domain.repository.BugRepository;
import com.aieducenter.aiplatform.business.task.domain.repository.TaskRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 任务生命周期用例（A4 §2/§3/§6）：建任务（处 advance 守卫——开发→测试唯一
 * 触发）/ start / submit（载荷两种形状校验 + 暂存）/ confirm（一事务内 Bug
 * 批量落库或复测翻态 + TaskCompleted AFTER_COMMIT）/ reject / cancel。
 *
 * <p>事务形态（照 WorkspaceLifecycleAppService 模式）：confirm 的任务终态翻、
 * Bug 入库/翻态与 {@link TaskCompleted} 发布收进同一短事务——事件经 PUBLISHER
 * 端口在事务内发出、订阅方按 AFTER_COMMIT 语义在副作用真实落定后收到
 * （A1 §4.1）；幂等以状态机守门（CONFIRMED 才发，重复确认被 TASK_002 拒绝）。
 * SSE {@code task-updated} 一律事务提交后发射（编排层发射制，ADR-0001）。
 * 期联动（A4 §5）在任务落库前先行——advance 失败窗口的后果是阶段先进而任务
 * 缺席，可由再建任务收敛（良性），反向则留下未触发的开发段。</p>
 */
@Service
@Slf4j
public class TaskLifecycleAppService {

    private final TaskRepository taskRepository;
    private final BugRepository bugRepository;
    private final ProjectAgentTaskAppService agentTaskAppService;
    private final ProjectQueryAppService projectQueryAppService;
    private final AccountAppService accountAppService;
    private final TaskQueryAppService queryAppService;
    private final PlatformNotificationAppService notificationAppService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final FixDispatchAppService fixDispatchAppService;
    private final ProjectKnowledgeAppService knowledgeAppService;

    public TaskLifecycleAppService(TaskRepository taskRepository,
                                   BugRepository bugRepository,
                                   ProjectAgentTaskAppService agentTaskAppService,
                                   ProjectQueryAppService projectQueryAppService,
                                   AccountAppService accountAppService,
                                   TaskQueryAppService queryAppService,
                                   PlatformNotificationAppService notificationAppService,
                                   ApplicationEventPublisher eventPublisher,
                                   TransactionTemplate transactionTemplate,
                                   ObjectMapper objectMapper,
                                   FixDispatchAppService fixDispatchAppService,
                                   ProjectKnowledgeAppService knowledgeAppService) {
        this.taskRepository = taskRepository;
        this.bugRepository = bugRepository;
        this.agentTaskAppService = agentTaskAppService;
        this.projectQueryAppService = projectQueryAppService;
        this.accountAppService = accountAppService;
        this.queryAppService = queryAppService;
        this.notificationAppService = notificationAppService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.fixDispatchAppService = fixDispatchAppService;
        this.knowledgeAppService = knowledgeAppService;
    }

    /**
     * 建测试任务（dev，type 固定 TEST）：项目寻址 + owner 守卫 → 指派账号校验 →
     * 期 advance 守卫（开发段才推进，A4 §5）→ 落库 PUBLISHED → SSE。
     *
     * @throws ApplicationException PRJ_001 项目不存在；TASK_009 非项目归属账号；
     *                              TASK_008 指派账号不存在
     */
    public TaskResponse create(String projectId, CreateTaskCommand command) {
        Long parsed = projectQueryAppService.requireProjectId(projectId);
        return createInternal(parsed, command, null);
    }

    /**
     * 转任务建任务（A1 §3.1 第 1 步，#27 回填链起点）：settle(Deferred) 关等待点
     * 后由 project 侧经端口调入（waitId 不透明引用随任务落库——REST 面不收，
     * TaskCompleted 据此续跑原会话）。守卫链与 {@link #create} 同构（项目寻址
     * 由调用方先行）。
     *
     * @throws ApplicationException TASK_009 非项目归属账号（403）；TASK_008 指派
     *                              账号不存在
     */
    public TaskResponse createFromWait(Long projectId, String waitId,
                                       CreateTaskCommand command) {
        return createInternal(projectId, command, waitId);
    }

    /** 建任务共用内核（项目寻址后）：owner → 指派 → advance 守卫 → 落库 → SSE。 */
    private TaskResponse createInternal(Long projectId, CreateTaskCommand command, String waitId) {
        requireProjectOwner(projectId);
        if (!accountAppService.exists(command.assigneeAccountId())) {
            throw new ApplicationException(TaskMessage.ASSIGNEE_NOT_FOUND);
        }
        agentTaskAppService.advanceToTestOnTestTaskCreation(projectId);
        Task task = taskRepository.save(Task.publish(projectId, TaskType.TEST,
                command.title(), command.content(), command.assigneeAccountId(), waitId));
        emitTaskUpdated(task);
        return queryAppService.assemble(task);
    }

    /**
     * OPC start：已发布 → 执行中（仅指派本人）。
     *
     * @throws ApplicationException TASK_007 任务不存在；TASK_004 非指派本人；
     *                              TASK_002 非法迁移（409）
     */
    public TaskResponse start(Long taskId) {
        Task task = requireTask(taskId);
        task.requireAssignee(RequestContext.getUserId());
        task.start();
        Task saved = taskRepository.save(task);
        emitTaskUpdated(saved);
        return queryAppService.assemble(saved);
    }

    /**
     * OPC submit（A4 §3 两种形状）：首轮 {report, bugs}（空清单允许——测试全过）
     * / 复测 {report, results}；形状与复测目标校验先行，载荷 JSON 暂存
     * {@code submitted_payload}（驳回重交覆盖，驳回字段清空）。
     *
     * @throws ApplicationException TASK_006 载荷形状不合法；TASK_005 复测目标
     *                              Bug 不存在/非本项目；TASK_004/002 同 start
     */
    public TaskResponse submit(Long taskId, SubmitTaskCommand command) {
        Task task = requireTask(taskId);
        task.requireAssignee(RequestContext.getUserId());
        requireWellFormed(command);
        requireRetestTargetsInProject(command, task.getProjectId());
        task.submit(toJson(command));
        Task saved = taskRepository.save(task);
        emitTaskUpdated(saved);
        return queryAppService.assemble(saved);
    }

    /**
     * dev confirm（A4 §3 确认时点；owner 守卫防自确认）：一事务内——任务终态 +
     * Bug 批量落库（首轮 OPEN）或复测翻态（pass → VERIFIED / fail → 退回 OPEN）
     * + TaskCompleted 应用事件（AFTER_COMMIT）。空 Bug 清单允许：确认后无入库，
     * G3 直接就绪。事务块之后：知识摄取（A5 §1——测试报告 + OPEN 时刻的 Bug，
     * 失败降级不炸）→ 修复派发（A4 §4 触发①②——有 OPEN 可派才起链，无则空转；
     * in-flight 幂等门在链内）。
     *
     * @throws ApplicationException TASK_007/002/005/006 同上（载荷在事务内复验）；
     *                              TASK_009 非项目归属账号（403）
     */
    public TaskDetailResponse confirm(Long taskId) {
        Task task = requireTask(taskId);
        requireProjectOwner(task.getProjectId());
        ConfirmedOutcome outcome = transactionTemplate.execute(tx -> {
            // 状态守卫先行（重复确认 TASK_002 幂等挡门），载荷复验随后——任一失败
            // 整事务回滚（任务终态与 Bug 入库/翻态要么全落要么全无）
            task.confirm();
            taskRepository.save(task);
            SubmitTaskCommand payload = parseStored(task);
            List<Bug> openedBugs = payload.bugs() == null ? List.of()
                    : bugRepository.saveAll(payload.bugs().stream()
                            .map(bug -> Bug.openOf(task.getProjectId(), task.getId(),
                                    bug.title(), bug.description(), bug.reproSteps(),
                                    bug.severity()))
                            .toList());
            if (payload.bugs() == null) {
                payload.results().forEach(result -> {
                    Bug bug = requireProjectBug(result.bugId(), task.getProjectId());
                    bug.applyRetestResult(result.pass());
                    bugRepository.save(bug);
                });
            }
            eventPublisher.publishApplicationEvent(TaskCompleted.of(
                    task.getId().toString(), task.getType().name(),
                    task.getAssigneeAccountId().toString(),
                    task.getConfirmedAt().atZone(ZoneId.systemDefault()).toInstant(),
                    task.getWaitId(), summaryOf(payload)));
            return new ConfirmedOutcome(payload, openedBugs);
        });
        emitTaskUpdated(task);
        // AFTER_COMMIT 语义（事务块之后）：知识摄取（A5 §1）——测试报告分块 +
        // Bug 一条一块（仅 OPEN 时刻一次，复测翻态不摄取）；失败降级不炸。
        // 先于修复派发：修复 run 的检索注入可命中刚入库的素材
        knowledgeAppService.indexTestReport(task.getProjectId(), task.getId(),
                task.getTitle(), outcome.payload().report());
        outcome.openedBugs().forEach(bug -> knowledgeAppService.indexBug(
                task.getProjectId(), bug.getId(), bug.getTitle(), bug.getDescription(),
                bug.getReproSteps(), bug.getSeverity().getName()));
        // 修复链起跑（首轮入库/复测退回均有 OPEN 可派）
        fixDispatchAppService.onConfirmed(task.getProjectId());
        return queryAppService.detailOf(task);
    }

    /**
     * bogus Bug 手工关闭（A4 §4，#27）：reason 必填 → VERIFIED + closed_reason
     * （复测通过唯一关闭态的带理由别名动作，不加第四态）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；TASK_005 Bug 不存在/跨项目；
     *                              TASK_010 reason 空；TASK_009 非归属（403）
     */
    public BugResponse closeBug(String projectId, Long bugId, String reason) {
        Long parsed = projectQueryAppService.requireProjectId(projectId);
        requireProjectOwner(parsed);
        Bug bug = requireProjectBug(bugId, parsed);
        bug.closeManually(reason); // reason 必填由聚合不变量兜底（REST 面 @NotBlank 先行同码）
        Bug saved = bugRepository.save(bug);
        return queryAppService.bugOf(saved);
    }

    /**
     * dev reject（reason 必填）：已提交 → 执行中退回重做。
     *
     * @throws ApplicationException TASK_007 任务不存在；TASK_003 reason 空；
     *                              TASK_002 非已提交（409）；TASK_009 非归属（403）
     */
    public TaskResponse reject(Long taskId, String reason) {
        Task task = requireTask(taskId);
        requireProjectOwner(task.getProjectId());
        task.reject(reason); // reason 必填由聚合不变量兜底（REST 面 @NotBlank 先行同码）
        Task saved = taskRepository.save(task);
        emitTaskUpdated(saved);
        return queryAppService.assemble(saved);
    }

    /**
     * dev cancel：已发布/执行中 → 已取消。**已提交不能取消只能驳回**（A4 §2）。
     *
     * @throws ApplicationException TASK_007 任务不存在；TASK_002 已提交/终态（409）；
     *                              TASK_009 非归属（403）
     */
    public TaskResponse cancel(Long taskId) {
        Task task = requireTask(taskId);
        requireProjectOwner(task.getProjectId());
        task.cancel();
        Task saved = taskRepository.save(task);
        emitTaskUpdated(saved);
        return queryAppService.assemble(saved);
    }

    // ---------- 内部 ----------

    /** confirm 事务产物：载荷（知识摄取复用）+ 本轮新开的 Bug（OPEN 时刻摄取锚）。 */
    private record ConfirmedOutcome(SubmitTaskCommand payload, List<Bug> openedBugs) {
    }

    /** dev 动作守卫（A4 §6 视角列）：仅项目归属账号（v1 dev 侧 = owner——
     * OPC assignee 不能建任务/确认/驳回/取消，防自确认；角色谓词拆票后升级）。 */
    private void requireProjectOwner(Long projectId) {
        if (!RequestContext.getUserId()
                .equals(projectQueryAppService.ownerAccountIdOf(projectId))) {
            throw new ApplicationException(TaskMessage.TASK_NOT_OWNER);
        }
    }

    /** 载荷形状守卫（TASK_006）：report 必填；bugs 与 results 二选一（含空清单）。 */
    private static void requireWellFormed(SubmitTaskCommand command) {
        if (command.report() == null || command.report().isBlank()
                || (command.bugs() != null) == (command.results() != null)) {
            throw new ApplicationException(TaskMessage.SUBMIT_PAYLOAD_INVALID);
        }
    }

    /** 复测目标守卫（TASK_005）：逐条 bugId 存在 ∧ 属于本项目（提交时 fail-fast，
     * 确认时事务内复验）。 */
    private void requireRetestTargetsInProject(SubmitTaskCommand command, Long projectId) {
        if (command.results() == null) {
            return;
        }
        command.results().forEach(result ->
                requireProjectBug(result.bugId(), projectId));
    }

    private Bug requireProjectBug(Long bugId, Long projectId) {
        Bug bug = bugRepository.findById(bugId)
                .orElseThrow(() -> new ApplicationException(TaskMessage.BUG_NOT_FOUND));
        if (!bug.getProjectId().equals(projectId)) {
            throw new ApplicationException(TaskMessage.BUG_NOT_FOUND); // 跨项目引用同 404
        }
        return bug;
    }

    /** 暂存载荷复验（确认时事务内）：缺失/损坏即 TASK_006——已提交任务的载荷
     * 不变量由 submit 守卫保证，此处防御数据异常。 */
    private SubmitTaskCommand parseStored(Task task) {
        String json = task.getSubmittedPayload();
        if (json == null || json.isBlank()) {
            throw new ApplicationException(TaskMessage.SUBMIT_PAYLOAD_INVALID);
        }
        try {
            return objectMapper.readValue(json, SubmitTaskCommand.class);
        } catch (JsonProcessingException e) {
            log.warn("任务 {} 暂存载荷解析失败", task.getId(), e);
            throw new ApplicationException(TaskMessage.SUBMIT_PAYLOAD_INVALID);
        }
    }

    private String toJson(SubmitTaskCommand command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException e) {
            throw new ApplicationException(TaskMessage.SUBMIT_PAYLOAD_INVALID);
        }
    }

    /** TaskCompleted 的 summary（A4 §8）：测试报告 + Bug 清单 / 复测结果——
     * 续跑 prompt 的素材（#27 回填）。 */
    private static String summaryOf(SubmitTaskCommand payload) {
        StringBuilder summary = new StringBuilder(payload.report().strip());
        if (payload.bugs() != null) {
            summary.append("\nBug 清单（").append(payload.bugs().size()).append(" 条）：");
            payload.bugs().forEach(bug -> summary.append("\n- [")
                    .append(bug.severity().getName()).append("] ").append(bug.title()));
        } else {
            summary.append("\n复测结果：");
            payload.results().forEach(result -> summary.append("\n- Bug ")
                    .append(result.bugId()).append(result.pass() ? "：通过" : "：未通过")
                    .append(result.note() == null ? "" : "（" + result.note() + "）"));
        }
        return summary.toString();
    }

    /** SSE task-updated（每次迁移一条，含创建；事务提交后发射）。 */
    private void emitTaskUpdated(Task task) {
        notificationAppService.publish(TaskEventTypes.TASK_UPDATED, Map.of(
                TaskEventTypes.PROJECT_ID_FIELD, task.getProjectId().toString(),
                TaskEventTypes.TASK_ID_FIELD, task.getId().toString(),
                TaskEventTypes.STATUS_FIELD, task.getStatus().name()));
    }

    private Task requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ApplicationException(TaskMessage.TASK_NOT_FOUND));
    }
}
