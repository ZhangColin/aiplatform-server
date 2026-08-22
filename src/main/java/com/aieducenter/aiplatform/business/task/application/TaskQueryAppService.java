package com.aieducenter.aiplatform.business.task.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aieducenter.aiplatform.business.identity.application.AccountAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectBriefResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.BugResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskCardResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskDetailResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskTodoSource;
import com.aieducenter.aiplatform.business.task.domain.aggregate.Bug;
import com.aieducenter.aiplatform.business.task.domain.aggregate.Task;
import com.aieducenter.aiplatform.business.task.domain.enums.BugStatus;
import com.aieducenter.aiplatform.business.task.domain.enums.TaskStatus;
import com.aieducenter.aiplatform.business.task.domain.error.TaskMessage;
import com.aieducenter.aiplatform.business.task.domain.repository.BugRepository;
import com.aieducenter.aiplatform.business.task.domain.repository.TaskRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 任务读侧用例（A4 §6/§7）：dev 项目任务全量 / opc 指派清单（assignee=me +
 * 最小项目上下文）/ 共用详情（assignee ∨ 项目 owner 谓词——A2 归属半边）/
 * 项目 Bug 面板 + workbench 四型待办的投影源（A2 §4 接线落地，量小全量读
 * 内存裁决）。写侧归 {@link TaskLifecycleAppService}。
 */
@Service
@Slf4j
public class TaskQueryAppService {

    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(
            TaskStatus.PUBLISHED, TaskStatus.IN_PROGRESS, TaskStatus.SUBMITTED);

    private final TaskRepository taskRepository;
    private final BugRepository bugRepository;
    private final ProjectQueryAppService projectQueryAppService;
    private final AccountAppService accountAppService;
    private final ObjectMapper objectMapper;

    public TaskQueryAppService(TaskRepository taskRepository,
                               BugRepository bugRepository,
                               ProjectQueryAppService projectQueryAppService,
                               AccountAppService accountAppService,
                               ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.bugRepository = bugRepository;
        this.projectQueryAppService = projectQueryAppService;
        this.accountAppService = accountAppService;
        this.objectMapper = objectMapper;
    }

    /**
     * 项目任务列表（dev 全量，新→旧）：含状态/驳回理由/提交载荷——确认与驳回
     * 的裁决输入。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public List<TaskResponse> listByProject(String projectId) {
        Long parsed = requireProject(projectId);
        return assembleAll(taskRepository.findByProjectId(parsed).stream()
                .sorted(Comparator.comparing(Task::getCreatedAt).reversed())
                .toList());
    }

    /**
     * 指派给我的任务（opc 跨项目，新→旧）：assignee=me 资源归属过滤（A4 §7——
     * 非角色挡端点，两谓词模型的归属半边）；卡片带最小项目上下文（项目名 +
     * 预览地址；项目已删的残留任务跳过——卡片无处导航）。
     */
    public List<TaskCardResponse> myTasks() {
        Long me = RequestContext.getUserId();
        List<Task> tasks = taskRepository.findByAssigneeAccountId(me).stream()
                .sorted(Comparator.comparing(Task::getCreatedAt).reversed())
                .toList();
        Map<Long, ProjectBriefResponse> briefs = projectQueryAppService.projectBriefs(
                tasks.stream().map(Task::getProjectId).collect(Collectors.toSet()));
        return tasks.stream()
                .map(task -> toCard(task, briefs.get(task.getProjectId())))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 任务详情（opc/dev 共用，A4 §6）：opc 校验 assignee=me（A4 §7 归属谓词；
     * 项目 owner 视为 dev 侧放行——v1 两账号下即平台方）。带项目 Bug 清单
     * （复测表单 bugId 源 / dev 确认复审对照）。
     *
     * @throws ApplicationException TASK_007 任务不存在；TASK_004 非指派亦非
     *                              项目 owner（403）
     */
    public TaskDetailResponse detail(Long taskId) {
        Task task = requireTask(taskId);
        Long requester = RequestContext.getUserId();
        boolean assigneeOrOwner = Objects.equals(task.getAssigneeAccountId(), requester)
                || Objects.equals(requester,
                        projectQueryAppService.ownerAccountIdOf(task.getProjectId()));
        if (!assigneeOrOwner) {
            throw new ApplicationException(TaskMessage.NOT_ASSIGNEE);
        }
        return detailOf(task);
    }

    /** 详情组包（无守卫）：生命周期用例确认/驳回后回读复用——动作已过各自的
     * 守卫，此处不再以 REST 谓词拦自己。 */
    TaskDetailResponse detailOf(Task task) {
        ProjectBriefResponse brief = projectQueryAppService
                .projectBriefs(Set.of(task.getProjectId())).get(task.getProjectId());
        List<BugResponse> bugs = bugRepository.findByProjectId(task.getProjectId()).stream()
                .map(TaskQueryAppService::toBugResponse)
                .sorted(Comparator.comparing(BugResponse::createdAt).reversed())
                .toList();
        return new TaskDetailResponse(assemble(task), toBrief(brief), bugs);
    }

    /**
     * 项目 Bug 清单（dev 面板，新→旧）：状态/fix_run_id/fix_note/closed_reason
     * 全带（修复编排链 #27 的呈现面先就位）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public List<BugResponse> bugs(String projectId) {
        Long parsed = requireProject(projectId);
        return bugRepository.findByProjectId(parsed).stream()
                .map(TaskQueryAppService::toBugResponse)
                .sorted(Comparator.comparing(BugResponse::createdAt).reversed())
                .toList();
    }

    // ---------- workbench 四型待办投影源（A2 §4 / A4 §7 澄清表） ----------

    /** TASK_SUBMITTED 源：存在 status=SUBMITTED 的任务（dev 逐任务一条）。 */
    public List<TaskTodoSource> submittedTodoSources() {
        return taskRepository.findByStatusIn(List.of(TaskStatus.SUBMITTED)).stream()
                .map(TaskQueryAppService::toTodoSource)
                .toList();
    }

    /**
     * RETEST_READY 源（A4 §7 澄清表）：存在 FIXED Bug ∧ 无进行中测试任务
     * （PUBLISHED/IN_PROGRESS/SUBMITTED）∧ 无 in-flight 修复（OPEN ∧
     * fix_run_id 非空——#27 修复链运行中）。逐项目一条，since = 最后一条
     * FIXED 翻态时刻（taskId/title 无项目级语义，置 null）。
     */
    public List<TaskTodoSource> retestReadyProjects() {
        List<Bug> fixed = bugRepository.findByStatus(BugStatus.FIXED);
        if (fixed.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Bug>> fixedByProject =
                fixed.stream().collect(Collectors.groupingBy(Bug::getProjectId));
        Set<Long> activeProjectIds = taskRepository.findByStatusIn(ACTIVE_STATUSES).stream()
                .map(Task::getProjectId)
                .collect(Collectors.toSet());
        Set<Long> inFlightProjectIds = bugRepository.findByStatus(BugStatus.OPEN).stream()
                .filter(bug -> bug.getFixRunId() != null)
                .map(Bug::getProjectId)
                .collect(Collectors.toSet());
        return fixedByProject.entrySet().stream()
                .filter(entry -> !activeProjectIds.contains(entry.getKey())
                        && !inFlightProjectIds.contains(entry.getKey()))
                .map(entry -> new TaskTodoSource(null, entry.getKey().toString(), null,
                        toInstant(entry.getValue().stream()
                                .map(TaskQueryAppService::changeAt)
                                .max(Comparator.naturalOrder())
                                .orElseThrow())))
                .toList();
    }

    /** NEW_TASK 源（opc）：assignee=me ∧ PUBLISHED。 */
    public List<TaskTodoSource> publishedTodoSources(Long accountId) {
        return taskRepository.findByAssigneeAccountId(accountId).stream()
                .filter(task -> task.getStatus() == TaskStatus.PUBLISHED)
                .map(TaskQueryAppService::toTodoSource)
                .toList();
    }

    /** TASK_REJECTED 源（opc）：assignee=me ∧ IN_PROGRESS ∧ rejected_at 非空
     * （重新提交即离开）。 */
    public List<TaskTodoSource> rejectedTodoSources(Long accountId) {
        return taskRepository.findByAssigneeAccountId(accountId).stream()
                .filter(task -> task.getStatus() == TaskStatus.IN_PROGRESS
                        && task.getRejectedAt() != null)
                .map(task -> new TaskTodoSource(task.getId().toString(),
                        task.getProjectId().toString(), task.getTitle(),
                        task.getRejectedAt().atZone(ZoneId.systemDefault()).toInstant()))
                .toList();
    }

    // ---------- 组装（生命周期用例复用） ----------

    /** 单 Bug 响应组装（手工关闭等单行回读）。 */
    BugResponse bugOf(Bug bug) {
        return toBugResponse(bug);
    }

    /** 单任务响应组装（批量显示名走 {@link #assembleAll}）。 */
    TaskResponse assemble(Task task) {
        return assembleAll(List.of(task)).get(0);
    }

    private List<TaskResponse> assembleAll(List<Task> tasks) {
        Map<Long, String> names = accountAppService.namesByIds(tasks.stream()
                .map(Task::getAssigneeAccountId).collect(Collectors.toSet()));
        return tasks.stream().map(task -> toResponse(task,
                names.get(task.getAssigneeAccountId()))).toList();
    }

    private TaskResponse toResponse(Task task, String assigneeName) {
        return new TaskResponse(task.getId().toString(), task.getProjectId().toString(),
                task.getType(), task.getType().getName(), task.getTitle(),
                task.getContent(), task.getAssigneeAccountId(), assigneeName,
                task.getStatus(), task.getStatus().getName(), task.getWaitId(),
                payloadAsMap(task), task.getRejectReason(), task.getRejectedAt(),
                task.getConfirmedAt(), task.getCreatedAt(), task.getUpdatedAt());
    }

    /** 载荷 JSON → 对象形态（null = 未提交；损坏载荷降级 null 不炸列表）。 */
    private Map<String, Object> payloadAsMap(Task task) {
        String json = task.getSubmittedPayload();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("任务 {} 暂存载荷读取降级（列表仍返回）", task.getId(), e);
            return null;
        }
    }

    private static TaskCardResponse toCard(Task task, ProjectBriefResponse brief) {
        if (brief == null) {
            return null; // 项目已删残留：卡片无处导航
        }
        return new TaskCardResponse(task.getId().toString(), task.getProjectId().toString(),
                new TaskCardResponse.ProjectBrief(brief.name(), brief.previewUrl()),
                task.getTitle(), task.getContent(), task.getStatus(),
                task.getStatus().getName(), task.getRejectReason(), task.getRejectedAt(),
                task.getCreatedAt());
    }

    private static TaskCardResponse.ProjectBrief toBrief(ProjectBriefResponse brief) {
        return brief == null ? null
                : new TaskCardResponse.ProjectBrief(brief.name(), brief.previewUrl());
    }

    private static BugResponse toBugResponse(Bug bug) {
        return new BugResponse(bug.getId().toString(), bug.getProjectId().toString(),
                bug.getSourceTaskId().toString(), bug.getTitle(), bug.getDescription(),
                bug.getReproSteps(), bug.getSeverity(), bug.getSeverity().getName(),
                bug.getStatus(), bug.getStatus().getName(), bug.getFixRunId(),
                bug.getFixNote(), bug.getClosedReason(), bug.getCreatedAt(),
                bug.getUpdatedAt());
    }

    private static TaskTodoSource toTodoSource(Task task) {
        return new TaskTodoSource(task.getId().toString(), task.getProjectId().toString(),
                task.getTitle(), toInstant(changeAt(task)));
    }

    /** 源状态时刻：updatedAt 即最近一次迁移（提交/发布落态时刻），审计未落时退 createdAt。 */
    private static LocalDateTime changeAt(Task task) {
        return task.getUpdatedAt() != null ? task.getUpdatedAt() : task.getCreatedAt();
    }

    private static LocalDateTime changeAt(Bug bug) {
        return bug.getUpdatedAt() != null ? bug.getUpdatedAt() : bug.getCreatedAt();
    }

    private static Instant toInstant(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant();
    }

    private Task requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ApplicationException(TaskMessage.TASK_NOT_FOUND));
    }

    /** 项目寻址收口（PRJ_001——经 project BC 应用层，端点层工具不外借）。 */
    private Long requireProject(String projectId) {
        return projectQueryAppService.requireProjectId(projectId);
    }
}
