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
import com.aieducenter.aiplatform.business.task.application.dto.command.CreateTaskCommand;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskResponse;

/**
 * 项目任务 REST 面（dev，A4 §6）：建测试任务（指派必填，处开发→测试 advance
 * 守卫）+ 项目任务全量（含状态/驳回理由/提交载荷——确认与驳回的裁决输入）。
 * opc 指派清单与任务操作归 {@code TaskController}。
 */
@RestController
@RequestMapping("/api/projects/{id}/tasks")
@Validated
@Tag(name = "项目任务", description = "建测试任务 / 项目任务列表（business.task，dev 视角）")
public class ProjectTaskController {

    private final TaskLifecycleAppService lifecycleAppService;
    private final TaskQueryAppService queryAppService;

    public ProjectTaskController(TaskLifecycleAppService lifecycleAppService,
                                 TaskQueryAppService queryAppService) {
        this.lifecycleAppService = lifecycleAppService;
        this.queryAppService = queryAppService;
    }

    @PostMapping
    @Operation(summary = "建测试任务（指派 OPC 账号）",
            description = "type 固定 TEST（v1 单值）；assignee 必填（指派与领取分离，v1 只指派——"
                    + "账号清单 GET /api/accounts）。期在开发段时本动作是开发→测试的唯一触发"
                    + "（advance + stage-changed；复测/期后不动）。指派账号不存在 404 TASK_008。"
                    + "SSE：task-updated(status=PUBLISHED)。转任务来源（waitId）是程序化入参，"
                    + "REST 面不收（#27 回填）")
    public ApiResponse<TaskResponse> create(@PathVariable String id,
                                            @Valid @RequestBody CreateTaskCommand command) {
        return ApiResponse.ok(lifecycleAppService.create(id, command));
    }

    @GetMapping
    @Operation(summary = "项目任务列表（全量，新→旧）",
            description = "含状态/驳回理由/提交载荷（确认与驳回的裁决输入）；"
                    + "status：1=已发布 2=执行中 3=已提交 4=已确认 5=已取消")
    public ApiResponse<List<TaskResponse>> list(@PathVariable String id) {
        return ApiResponse.ok(queryAppService.listByProject(id));
    }
}
