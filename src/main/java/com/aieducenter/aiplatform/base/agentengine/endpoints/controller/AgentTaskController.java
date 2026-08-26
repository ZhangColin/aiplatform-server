package com.aieducenter.aiplatform.base.agentengine.endpoints.controller;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

import com.aieducenter.aiplatform.base.agentengine.application.AgentSessionAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentTaskAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentPermissionCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentQuestionReplyCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentTaskDispatchCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentSessionResponse;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentTaskResponse;

/**
 * 开发智能体任务与交互最小 REST 面（片2a，按 workspaceId 寻址、零业务概念）：
 * 任务下发（runId 生成返回）/ 会话查询（重启可寻址）/ 问答 / 权限审批 / 引擎健康。
 * 正式消费面在 business.project（片5 总装：按项目编排角色卡与阶段）。
 */
@RestController
@Validated
@RequestMapping("/api/workspaces/{workspaceId}/agent")
@Tag(name = "开发智能体", description = "引擎适配、任务下发与问答/权限交互（base.agentengine，按工作区寻址）")
public class AgentTaskController {

    private final AgentTaskAppService taskAppService;
    private final AgentSessionAppService sessionAppService;

    public AgentTaskController(AgentTaskAppService taskAppService,
                               AgentSessionAppService sessionAppService) {
        this.taskAppService = taskAppService;
        this.sessionAppService = sessionAppService;
    }

    @PostMapping("/tasks")
    @Operation(summary = "下发任务", description = """
            runId 平台生成并随响应返回（该运行全部 agent 流事件携带）。异步：立即返回，
            过程事件经 `GET /api/agent-events` 透传。systemPrompt/modelId 是入参（适配层
            零角色概念）；sessionId 非空 = 复用既有会话续跑；engine 缺省 = 后台全局
            配置的生效引擎（`GET /api/admin/engine-config`，未配置时 opencode；
            可选值见 `GET /api/agent-engines`）。""")
    public ApiResponse<AgentTaskResponse> dispatch(
            @Parameter(description = "工作区 id（TSID 字符串）") @PathVariable String workspaceId,
            @Valid @RequestBody AgentTaskDispatchCommand command) {
        return ApiResponse.ok(taskAppService.dispatch(workspaceId, command));
    }

    @GetMapping("/sessions")
    @Operation(summary = "工作区会话列表", description = "按 workspaceId 寻址、跨重启存活（服务重启后照常可查，续跑缝的寻址面）。")
    public ApiResponse<List<AgentSessionResponse>> sessions(
            @Parameter(description = "工作区 id") @PathVariable String workspaceId) {
        return ApiResponse.ok(sessionAppService.listByWorkspace(workspaceId));
    }

    @GetMapping("/sessions/{sessionId}/questions")
    @Operation(summary = "会话内待答问题", description = """
            引擎载荷原样（底座不解释：question/header/options/...）。无问答能力的引擎
            （dsh）恒空——能力矩阵见 `GET /api/agent-engines`。""")
    public ApiResponse<List<Map<String, Object>>> questions(
            @Parameter(description = "工作区 id") @PathVariable String workspaceId,
            @Parameter(description = "引擎会话 id") @PathVariable String sessionId) {
        return ApiResponse.ok(taskAppService.pendingQuestions(workspaceId, sessionId));
    }

    @PostMapping("/sessions/{sessionId}/questions/{requestId}/reply")
    @Operation(summary = "回答问题", description = "answers 按问题顺序，每项 = 该问题选中的标签列表（custom 输入也作为标签）；agent 收到答复继续跑。")
    public ApiResponse<Void> replyQuestions(
            @Parameter(description = "工作区 id") @PathVariable String workspaceId,
            @Parameter(description = "引擎会话 id") @PathVariable String sessionId,
            @Parameter(description = "问题请求 id") @PathVariable String requestId,
            @Valid @RequestBody AgentQuestionReplyCommand command) {
        taskAppService.replyQuestions(workspaceId, sessionId, requestId, command);
        return ApiResponse.ok();
    }

    @PostMapping("/sessions/{sessionId}/permissions/{permissionId}")
    @Operation(summary = "权限审批", description = "approve=true 批准（once）/ false 拒绝（reject）。无权限通道的引擎（dsh）为 no-op。")
    public ApiResponse<Void> replyPermission(
            @Parameter(description = "工作区 id") @PathVariable String workspaceId,
            @Parameter(description = "引擎会话 id") @PathVariable String sessionId,
            @Parameter(description = "权限请求 id") @PathVariable String permissionId,
            @Valid @RequestBody AgentPermissionCommand command) {
        taskAppService.replyPermission(workspaceId, sessionId, permissionId, command);
        return ApiResponse.ok();
    }

    @GetMapping("/engines/{engine}/health")
    @Operation(summary = "引擎健康", description = "opencode = 容器内 serve 可达（不拉起，只探活）；dsh = CLI 可用。")
    public ApiResponse<Boolean> health(
            @Parameter(description = "工作区 id") @PathVariable String workspaceId,
            @Parameter(description = "引擎名（opencode / dsh）") @PathVariable String engine) {
        return ApiResponse.ok(taskAppService.health(workspaceId, engine));
    }
}
