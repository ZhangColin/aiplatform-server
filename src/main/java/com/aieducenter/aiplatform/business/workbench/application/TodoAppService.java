package com.aieducenter.aiplatform.business.workbench.application;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.BaseCodeMessage;

import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ProvisionFailedWorkspaceResponse;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.GateReadyResponse;
import com.aieducenter.aiplatform.business.task.application.TaskQueryAppService;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskTodoSource;
import com.aieducenter.aiplatform.business.workbench.application.dto.response.TodoItemResponse;

/**
 * 待办用例（A2 §4/§5 + A4 §7 接线）：计算式投影——dev = AGENT_WAIT（跨项目
 * pending 等待点）/ GATE_PENDING（期门就绪）/ TASK_SUBMITTED（已提交待确认）/
 * RETEST_READY（可发复测）；opc = NEW_TASK / TASK_REJECTED（assignee=me）。
 * 四型谓词照 A4 §7 澄清表，事实源在 task BC 查询面。无表无状态：每次全量
 * 重算、新者在前、不分页（量小）；SSE 零新增——前端收既有平台通知
 * （wait-raised / stage-changed / task-updated）即重拉（事件让 UI 活，正确性
 * 走 REST，ADR-0001）。
 */
@Service
public class TodoAppService {

    /** 视角：dev（开发平台）/ opc（任务平台）。 */
    public static final String VIEW_DEV = "dev";
    public static final String VIEW_OPC = "opc";

    private final AgentWaitAppService agentWaitAppService;
    private final ProjectQueryAppService projectQueryAppService;
    private final TaskQueryAppService taskQueryAppService;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public TodoAppService(AgentWaitAppService agentWaitAppService,
                          ProjectQueryAppService projectQueryAppService,
                          TaskQueryAppService taskQueryAppService,
                          WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.agentWaitAppService = agentWaitAppService;
        this.projectQueryAppService = projectQueryAppService;
        this.taskQueryAppService = taskQueryAppService;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    /**
     * 待办列表：视角内各型投影合并、createdAt 倒序（新者在前）。
     *
     * @throws ApplicationException view 非 dev/opc（400——本上下文无错误码前缀，
     *         入参问题走全局 BAD_REQUEST 面，业务错误来自源上下文）
     */
    public List<TodoItemResponse> list(String view) {
        List<TodoItemResponse> todos = new ArrayList<>();
        if (VIEW_OPC.equals(normalizeView(view))) {
            // opc 资源归属半边（A4 §7）：assignee = 当前账号
            Long me = RequestContext.getUserId();
            todos.addAll(taskTodos(taskQueryAppService.publishedTodoSources(me),
                    TodoItemResponse.TYPE_NEW_TASK, title -> "「" + title + "」新任务，待开始"));
            todos.addAll(taskTodos(taskQueryAppService.rejectedTodoSources(me),
                    TodoItemResponse.TYPE_TASK_REJECTED, title -> "「" + title + "」被驳回，待重新提交"));
        } else {
            todos.addAll(agentWaitTodos());
            todos.addAll(gatePendingTodos());
            todos.addAll(taskTodos(taskQueryAppService.submittedTodoSources(),
                    TodoItemResponse.TYPE_TASK_SUBMITTED, title -> "「" + title + "」已提交，待确认"));
            todos.addAll(retestReadyTodos());
            todos.addAll(provisionFailedTodos());
        }
        return todos.stream()
                .sorted(Comparator.comparing(TodoItemResponse::createdAt).reversed())
                .toList();
    }

    // ---------- AGENT_WAIT：pending 等待点投影 ----------

    /** 等待点 → 待办（refId=waitId）；工作区无归属项目的等待点跳过——待办无处导航。 */
    private List<TodoItemResponse> agentWaitTodos() {
        List<WaitPointResponse> waits = agentWaitAppService.listPendingWaits();
        if (waits.isEmpty()) {
            return List.of();
        }
        Set<Long> workspaceIds = waits.stream()
                .map(wait -> Long.parseLong(wait.workspaceId()))
                .collect(Collectors.toSet());
        Map<Long, String> projectIdByWorkspace =
                projectQueryAppService.projectIdByWorkspaceId(workspaceIds);
        return waits.stream()
                .map(wait -> toAgentWaitTodo(wait,
                        projectIdByWorkspace.get(Long.parseLong(wait.workspaceId()))))
                .filter(Objects::nonNull)
                .toList();
    }

    private static TodoItemResponse toAgentWaitTodo(WaitPointResponse wait, String projectId) {
        if (projectId == null) {
            return null; // 非 dev 环境等待点 / 项目已删残留：不进 dev 待办
        }
        return new TodoItemResponse(TodoItemResponse.TYPE_AGENT_WAIT, projectId,
                wait.waitId(), agentWaitTitle(wait.kind()), wait.raisedAt());
    }

    /** 中性短文本：按等待点种类给动作短语，不透出智能体产出的 summary。 */
    private static String agentWaitTitle(WaitKind kind) {
        return switch (kind) {
            case QUESTION -> "智能体等待答复";
            case PERMISSION -> "智能体等待权限批准";
        };
    }

    // ---------- GATE_PENDING：期门就绪投影 ----------

    /** 门就绪 → 待办（refId=projectId，与 projectId 同值——拍板动作即期上）。 */
    private List<TodoItemResponse> gatePendingTodos() {
        return projectQueryAppService.listGateReady().stream()
                .map(TodoAppService::toGatePendingTodo)
                .toList();
    }

    private static TodoItemResponse toGatePendingTodo(GateReadyResponse gate) {
        return new TodoItemResponse(TodoItemResponse.TYPE_GATE_PENDING, gate.projectId(),
                gate.projectId(), "「" + gate.stageLabel() + "」门待拍板", gate.readySince());
    }

    // ---------- 任务型四型（A4 §7 接线） ----------

    /** 任务型待办（refId=taskId；title 带任务标题——人起标题非智能体产出）。 */
    private static List<TodoItemResponse> taskTodos(List<TaskTodoSource> sources,
                                                    String type, Function<String, String> title) {
        return sources.stream()
                .map(source -> new TodoItemResponse(type, source.projectId(),
                        source.taskId(), title.apply(source.title()), source.since()))
                .toList();
    }

    /** RETEST_READY → 待办（refId=projectId——动作是「发复测任务」，即项目上）。 */
    private List<TodoItemResponse> retestReadyTodos() {
        return taskQueryAppService.retestReadyProjects().stream()
                .map(source -> new TodoItemResponse(TodoItemResponse.TYPE_RETEST_READY,
                        source.projectId(), source.projectId(),
                        "Bug 已修复，可发复测任务", source.since()))
                .toList();
    }

    // ---------- WORKSPACE_PROVISION_FAILED：置备失败投影（#63 非侵入标记） ----------

    /** 置备失败工作区 → 待办（refId=workspaceId；工作区无归属项目的跳过——待办无处导航）。 */
    private List<TodoItemResponse> provisionFailedTodos() {
        List<ProvisionFailedWorkspaceResponse> failed =
                workspaceLifecycleAppService.listProvisionFailed();
        if (failed.isEmpty()) {
            return List.of();
        }
        // TSID 字符串形解析一次，按数值键寻址（projectIdByWorkspaceId 以 Long 为键）
        Map<Long, ProvisionFailedWorkspaceResponse> failedByWorkspace = failed.stream()
                .collect(Collectors.toMap(f -> Long.parseLong(f.workspaceId()), Function.identity()));
        Map<Long, String> projectIdByWorkspace =
                projectQueryAppService.projectIdByWorkspaceId(failedByWorkspace.keySet());
        return failedByWorkspace.entrySet().stream()
                .map(entry -> toProvisionFailedTodo(entry.getValue(),
                        projectIdByWorkspace.get(entry.getKey())))
                .filter(Objects::nonNull)
                .toList();
    }

    private static TodoItemResponse toProvisionFailedTodo(ProvisionFailedWorkspaceResponse failed,
                                                          String projectId) {
        if (projectId == null) {
            return null; // 工作区无归属项目（非 dev 环境 / 项目已删残留）：不进 dev 待办
        }
        return new TodoItemResponse(TodoItemResponse.TYPE_WORKSPACE_PROVISION_FAILED, projectId,
                failed.workspaceId(), "环境置备失败",
                failed.failedAt().atZone(ZoneId.systemDefault()).toInstant());
    }

    // ---------- 内部 ----------

    /** 视角归一（空白 = 缺省 dev）；不合法取值 400（全局 BAD_REQUEST 信封）。 */
    private static String normalizeView(String view) {
        if (view == null || view.isBlank()) {
            return VIEW_DEV;
        }
        String normalized = view.strip().toLowerCase(Locale.ROOT);
        if (!VIEW_DEV.equals(normalized) && !VIEW_OPC.equals(normalized)) {
            throw new ApplicationException(BaseCodeMessage.BAD_REQUEST);
        }
        return normalized;
    }
}
