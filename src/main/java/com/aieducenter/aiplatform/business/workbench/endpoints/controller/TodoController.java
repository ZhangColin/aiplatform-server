package com.aieducenter.aiplatform.business.workbench.endpoints.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.workbench.application.TodoAppService;
import com.aieducenter.aiplatform.business.workbench.application.dto.response.TodoItemResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 待办端点（A2 §4/§5）：计算式投影的 REST 面。无会话 401（/api/** 拦截面 +
 * 全局映射）；SSE 零新增——前端收既有平台通知（wait-raised / stage-changed /
 * 任务事件）即重拉本口。
 */
@Validated
@RestController
@Tag(name = "工作台", description = "待办列表（business.workbench，查询侧聚合）")
public class TodoController {

    private final TodoAppService appService;

    public TodoController(TodoAppService appService) {
        this.appService = appService;
    }

    @GetMapping("/api/todos")
    @Operation(summary = "待办列表", description = "view=dev|opc（缺省 dev）的计算式投影："
            + "AGENT_WAIT（refId=waitId）/ GATE_PENDING（refId=projectId），任务型随 A4；"
            + "不分页，新者在前。")
    public ApiResponse<List<TodoItemResponse>> todos(
            @RequestParam(name = "view", defaultValue = "dev") String view) {
        return ApiResponse.ok(appService.list(view));
    }
}
