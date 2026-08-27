package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.project.application.ProjectAgentTaskAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectWaitAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectAgentTaskCommand;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectWaitSettleCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectAgentTaskResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectWaitResponse;

/**
 * 项目智能体任务与等待点 REST 面（demo AgentController 的重写，B0 §2 片5）：
 * 下任务（角色卡 = 显式入参或阶段默认，SSE role-assigned → task-start → … →
 * task-finish）；运行终止（票 #38 逃生口：wait-settled(cancelled) × N →
 * task-finish(cancelled)）；等待点问答/权限答复（与开发平台共用同一套 wait 语义，A3 §5）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/agent")
@Validated
@Tag(name = "Project Agent", description = "项目智能体任务下发与等待点答复（agent 流挂 /api/agent-events?projectId=）")
public class ProjectAgentController {

    private final ProjectAgentTaskAppService taskAppService;
    private final ProjectWaitAppService waitAppService;

    public ProjectAgentController(ProjectAgentTaskAppService taskAppService,
                                  ProjectWaitAppService waitAppService) {
        this.taskAppService = taskAppService;
        this.waitAppService = waitAppService;
    }

    @PostMapping("/task")
    @Operation(summary = "下任务（手动 DEV/ARCH 或任意角色卡）",
            description = "角色缺省取当前阶段默认角色（无默认角色的阶段需显式指定，409 PRJ_004）。"
                    + "runId 随响应返回，SSE 帧序：role-assigned → task-start → session-created → 过程流 → task-finish。"
                    + "run 被接受即计入当前阶段任务计数（门禁输入）")
    public ApiResponse<ProjectAgentTaskResponse> dispatchTask(
            @PathVariable String projectId,
            @Valid @RequestBody ProjectAgentTaskCommand command) {
        return ApiResponse.ok(taskAppService.dispatchTask(parseId(projectId), command));
    }

    @PostMapping("/runs/{runId}/cancel")
    @Operation(summary = "终止运行（工作台顶栏终止 / 审批卡终止任务逃生口）",
            description = "终止一次进行中的 agent run（挂起等待点或在飞执行，含 BA 对话轮）。"
                    + "runId 解析：该 run 名下等待点行优先、无则回退会话最近运行，均查无 → 404。"
                    + "SSE 帧序：wait-settled(outcome=cancelled) × N → task-finish(finish=cancelled)"
                    + "（平台权威终态帧，引擎自然帧照透）。best-effort 恒 200：已终态/重复终止空转不炸；"
                    + "dsh 引擎不支持真终止（终止信号 no-op，平台帧照发）")
    public ApiResponse<Void> cancelRun(@PathVariable String projectId,
                                       @PathVariable String runId) {
        taskAppService.cancelRun(parseId(projectId), runId);
        return ApiResponse.ok();
    }

    @GetMapping("/waits")
    @Operation(summary = "项目的待处理等待点",
            description = "跨会话聚合 PENDING（新→旧）：问答（kind=QUESTION）与权限（kind=PERMISSION）；"
                    + "body 为引擎载荷原样（问题选项等）")
    public ApiResponse<List<ProjectWaitResponse>> pendingWaits(@PathVariable String projectId) {
        return ApiResponse.ok(waitAppService.pendingWaits(parseId(projectId)));
    }

    @PostMapping("/waits/{waitId}/settle")
    @Operation(summary = "答复等待点（问答答复 / 权限批准或拒绝 / 转任务）",
            description = "type=answer（answers=选项 label 二维）/ permission（approve）/ deferred（转任务："
                    + "task={title, content?, assigneeAccountId} 必填——关等待点 + 建任务存 waitId 引用，"
                    + "任务确认后自动复用原会话续跑（prompt=测试报告摘要））。agent 收到答复续跑；"
                    + "权限拒绝累计达上限由平台终止（不形成审批循环）；成功后 SSE wait-settled"
                    + "（outcome=answered/approved/denied/deferred）")
    public ApiResponse<Void> settle(@PathVariable String projectId,
                                    @PathVariable String waitId,
                                    @Valid @RequestBody ProjectWaitSettleCommand command) {
        waitAppService.settle(parseId(projectId), waitId, command);
        return ApiResponse.ok();
    }

    /** 寻址解析收口（{@link ProjectIds}，两 controller 共用）。 */
    private Long parseId(String projectId) {
        return ProjectIds.parse(projectId);
    }
}
