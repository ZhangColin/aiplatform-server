package com.aieducenter.aiplatform.base.agentengine.endpoints.controller;

import java.util.List;

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

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.base.agentengine.application.AgentTaskAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.SettleResult;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;

/**
 * 等待点最小 REST 面（片2b，A1 §1.1 口子①统一模型——按 workspaceId 中性寻址）：
 * 跨会话待处理聚合 / waitId 单查（业务层回填引用的读面）/ 三型答复（陈旧或不可
 * 续跑 409）。正式消费面在 business.project（片5 桥接：projectId 寻址 + SSE
 * wait-raised/wait-settled 发射，票 #22）。
 */
@RestController
@Validated
@RequestMapping("/api/workspaces/{workspaceId}/agent/waits")
@Tag(name = "等待点", description = "问答/权限统一模型的待处理聚合与答复（base.agentengine，按工作区寻址）")
public class AgentWaitController {

    private final AgentWaitAppService waitAppService;
    private final AgentTaskAppService taskAppService;

    public AgentWaitController(AgentWaitAppService waitAppService,
                               AgentTaskAppService taskAppService) {
        this.waitAppService = waitAppService;
        this.taskAppService = taskAppService;
    }

    @GetMapping
    @Operation(summary = "工作区待处理等待点", description = """
            跨会话聚合（新者在前），只有 PENDING。kind=QUESTION/PERMISSION 两类统一
            承载；body 为引擎载荷原样（底座不解释），summary 为适配器提取的中性短文本。""")
    public ApiResponse<List<WaitPointResponse>> pendingWaits(
            @Parameter(description = "工作区 id（TSID 字符串）") @PathVariable String workspaceId) {
        return ApiResponse.ok(waitAppService.pendingWaits(workspaceId));
    }

    @GetMapping("/{waitId}")
    @Operation(summary = "等待点单查", description = "waitId 全局寻址（含终态行——转任务/回填的引用读面）；不存在 404。")
    public ApiResponse<WaitPointResponse> wait(
            @Parameter(description = "工作区 id") @PathVariable String workspaceId,
            @Parameter(description = "等待点稳定标识") @PathVariable String waitId) {
        return ApiResponse.ok(waitAppService.wait(waitId)
                .orElseThrow(() -> new ApplicationException(AgentEngineMessage.WAIT_NOT_FOUND)));
    }

    @PostMapping("/{waitId}/settle")
    @Operation(summary = "答复等待点", description = """
            三型：`{"type":"answer","answers":[["Vue3"]]}`（问答）/ `{"type":"permission",
            "approve":false}`（权限）/ `{"type":"deferred","note":"转任务"}`（转任务关闭）。
            校验：非 PENDING 或会话不可续跑 → 409；等待点不存在 → 404；引擎不可达 → 502
            （保持 PENDING 可重试）。同 run 权限拒绝累计达上限（默认 3，可配）触发平台
            终止运行：abort + 等待点收口 + SSE wait-settled(outcome=cancelled) × N →
            task-finish(finish=cancelled)（平台权威终态帧，#38 与运行终止端点共用路径）。""")
    public ApiResponse<Void> settle(
            @Parameter(description = "工作区 id") @PathVariable String workspaceId,
            @Parameter(description = "等待点稳定标识") @PathVariable String waitId,
            @Valid @RequestBody WaitSettleCommand command) {
        SettleResult result = waitAppService.settle(workspaceId, waitId,
                command);
        if (result.denyCapped()) {
            taskAppService.terminateRun(workspaceId, result.engine(),
                    result.settled().sessionId(), result.settled().runId(), null);
        }
        return ApiResponse.ok();
    }
}
