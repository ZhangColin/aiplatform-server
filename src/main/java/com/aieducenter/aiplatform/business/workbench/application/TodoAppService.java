package com.aieducenter.aiplatform.business.workbench.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.BaseCodeMessage;

import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.GateReadyResponse;
import com.aieducenter.aiplatform.business.workbench.application.dto.response.TodoItemResponse;

/**
 * 待办用例（A2 §4/§5）：计算式投影——AGENT_WAIT（跨项目 pending 等待点）与
 * GATE_PENDING（期门就绪）两型；view=dev|opc 过滤视角，任务型待办随 A4 接
 * task 查询端口（v1 opc 空）。无表无状态：每次全量重算、新者在前、不分页
 * （量小）；SSE 零新增——前端收既有平台通知即重拉（事件让 UI 活，正确性
 * 走 REST，ADR-0001）。
 */
@Service
public class TodoAppService {

    /** 视角：dev（开发平台）/ opc（任务平台）。 */
    public static final String VIEW_DEV = "dev";
    public static final String VIEW_OPC = "opc";

    private final AgentWaitAppService agentWaitAppService;
    private final ProjectQueryAppService projectQueryAppService;

    public TodoAppService(AgentWaitAppService agentWaitAppService,
                          ProjectQueryAppService projectQueryAppService) {
        this.agentWaitAppService = agentWaitAppService;
        this.projectQueryAppService = projectQueryAppService;
    }

    /**
     * 待办列表：两型投影合并、createdAt 倒序（新者在前）。
     *
     * @throws ApplicationException view 非 dev/opc（400——本上下文无错误码前缀，
     *         入参问题走全局 BAD_REQUEST 面，业务错误来自源上下文）
     */
    public List<TodoItemResponse> list(String view) {
        if (VIEW_OPC.equals(normalizeView(view))) {
            return List.of(); // NEW_TASK / TASK_REJECTED 随 A4（#26）接线，v1 opc 空
        }
        List<TodoItemResponse> todos = new ArrayList<>();
        todos.addAll(agentWaitTodos());
        todos.addAll(gatePendingTodos());
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
