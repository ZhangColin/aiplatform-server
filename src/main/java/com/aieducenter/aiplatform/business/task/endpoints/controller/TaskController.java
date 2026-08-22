package com.aieducenter.aiplatform.business.task.endpoints.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.task.application.TaskLifecycleAppService;
import com.aieducenter.aiplatform.business.task.application.TaskQueryAppService;
import com.aieducenter.aiplatform.business.task.application.dto.command.RejectTaskCommand;
import com.aieducenter.aiplatform.business.task.application.dto.command.SubmitTaskCommand;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskCardResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskDetailResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskResponse;

/**
 * 任务平台 REST 面（opc 操作主面，dev 确认/驳回/取消共用寻址，A4 §6）：
 * 指派清单（assignee=me + 最小项目上下文）/ 详情 / start / submit / confirm /
 * reject / cancel。修复派发与 bogus 关闭（dispatch-fixes / close）随 #27。
 */
@RestController
@RequestMapping("/api/tasks")
@Validated
@Tag(name = "任务", description = "任务平台：指派清单 / 详情 / 执行与确认（business.task）")
public class TaskController {

    private final TaskLifecycleAppService lifecycleAppService;
    private final TaskQueryAppService queryAppService;

    public TaskController(TaskLifecycleAppService lifecycleAppService,
                          TaskQueryAppService queryAppService) {
        this.lifecycleAppService = lifecycleAppService;
        this.queryAppService = queryAppService;
    }

    @GetMapping
    @Operation(summary = "指派给我的任务（opc 跨项目，新→旧）",
            description = "assignee=me 资源归属过滤（A4 §7）；卡片带最小项目上下文"
                    + "（project.name + project.previewUrl——OPC 测试要看预览）与驳回理由")
    public ApiResponse<List<TaskCardResponse>> myTasks() {
        return ApiResponse.ok(queryAppService.myTasks());
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "任务详情（opc/dev 共用）",
            description = "opc 校验 assignee=me（项目 owner 视为 dev 侧放行，403 TASK_004）；"
                    + "带项目 Bug 清单（复测表单 bugId 源 / dev 确认复审对照）"
                    + "与提交载荷对象形态（首轮 {report, bugs[]} / 复测 {report, results[]}）")
    public ApiResponse<TaskDetailResponse> detail(@PathVariable String taskId) {
        return ApiResponse.ok(queryAppService.detail(TaskIds.parse(taskId)));
    }

    @PostMapping("/{taskId}/start")
    @Operation(summary = "开始任务（已发布 → 执行中）",
            description = "仅指派本人（403 TASK_004）；已开始/终态 409 TASK_002。"
                    + "SSE：task-updated(status=IN_PROGRESS)")
    public ApiResponse<TaskResponse> start(@PathVariable String taskId) {
        return ApiResponse.ok(lifecycleAppService.start(TaskIds.parse(taskId)));
    }

    @PostMapping("/{taskId}/submit")
    @Operation(summary = "提交任务（执行中 → 已提交）",
            description = "仅指派本人。载荷两种形状二选一（A4 §3）：首轮 "
                    + "{report, bugs:[{title, description, reproSteps, severity}]}（空清单允许"
                    + "——测试全过）；复测 {report, results:[{bugId, pass, note}]}（bugId 须为本项目"
                    + " Bug，404 TASK_005）。形状不合法 400 TASK_006。载荷暂存 submitted_payload，"
                    + "Bug 确认时才入库（驳回路径天然干净）。SSE：task-updated(status=SUBMITTED)")
    public ApiResponse<TaskResponse> submit(@PathVariable String taskId,
                                            @Valid @RequestBody SubmitTaskCommand command) {
        return ApiResponse.ok(lifecycleAppService.submit(TaskIds.parse(taskId), command));
    }

    @PostMapping("/{taskId}/confirm")
    @Operation(summary = "确认任务（已提交 → 已确认，dev）",
            description = "一事务内：首轮 → Bug 清单批量落库（OPEN，空清单即无入库、"
                    + "G3 直接就绪）；复测 → 逐条翻态（pass=true → VERIFIED 唯一关闭态 / "
                    + "false → 退回 OPEN，修复再派发随 #27）；并发 TaskCompleted 应用事件"
                    + "（AFTER_COMMIT——转任务回填续跑的锚，#27 消费）。幂等以状态机守门："
                    + "重复确认 409 TASK_002。SSE：task-updated(status=CONFIRMED)")
    public ApiResponse<TaskDetailResponse> confirm(@PathVariable String taskId) {
        return ApiResponse.ok(lifecycleAppService.confirm(TaskIds.parse(taskId)));
    }

    @PostMapping("/{taskId}/reject")
    @Operation(summary = "驳回任务（已提交 → 执行中退回，dev）",
            description = "reason 必填（400 TASK_003）；任务回到执行中、驳回理由/时间落库"
                    + "（OPC TASK_REJECTED 待办亮起）。SSE：task-updated(status=IN_PROGRESS)")
    public ApiResponse<TaskResponse> reject(@PathVariable String taskId,
                                            @Valid @RequestBody RejectTaskCommand command) {
        return ApiResponse.ok(lifecycleAppService.reject(TaskIds.parse(taskId), command.reason()));
    }

    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "取消任务（已发布/执行中 → 已取消，dev）",
            description = "已提交不能取消只能驳回（409 TASK_002）；终态不可再动。"
                    + "SSE：task-updated(status=CANCELLED)")
    public ApiResponse<TaskResponse> cancel(@PathVariable String taskId) {
        return ApiResponse.ok(lifecycleAppService.cancel(TaskIds.parse(taskId)));
    }
}
