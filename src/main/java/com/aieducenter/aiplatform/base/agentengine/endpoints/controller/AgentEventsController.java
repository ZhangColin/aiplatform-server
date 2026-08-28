package com.aieducenter.aiplatform.base.agentengine.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;

/**
 * agent 流 SSE 通道（通道二，ADR-0001）：{@code GET /api/agent-events}。
 *
 * <p>SSE 是呈现通道不是 REST——不套 ApiResponse，返回 {@link SseEmitter}；
 * 事件契约以名册正本（docs/spec/SSE事件清单.md）+ 本端点描述为准。</p>
 */
@RestController
@Validated
@RequestMapping("/api/agent-events")
@Tag(name = "事件中心 SSE", description = "SSE 呈现通道（非 REST，不套 ApiResponse）；事件名册正本见 docs/spec/SSE事件清单.md")
public class AgentEventsController {

    private final AgentStreamAppService appService;

    public AgentEventsController(AgentStreamAppService appService) {
        this.appService = appService;
    }

    /**
     * 订阅 agent 运行过程事件流。Last-Event-ID 请求头作新连/重连分野：无值（缺席或
     * 空串）= 新连接 = 先补发命中订阅过滤的最近缓冲帧（默认 1000 帧，
     * app.agent-stream.replay-depth）再进实时流；有值 = 断线重连 = 不补发
     * （REST 重查兜底，不做 seq 续传）。
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅 agent 流事件流（SSE）", description = """
            一次智能体运行的增量过程流（通道二，任务进度页组件级消费——看某个运行才挂）。
            带近期帧缓冲的热流：新连接（无 Last-Event-ID 值——缺席或空串）先补发命中订阅
            过滤的最近缓冲帧（默认 1000 帧，配置 app.agent-stream.replay-depth），再无缝进
            实时流；断线重连（浏览器自动携带非空 Last-Event-ID）不补发，前端对齐以 REST
            重查兜底。缓冲为单实例内存态（重启即失，多实例化时需重估）。

            信封：SSE name 恒为 `event`；id = `{runId}:{seq}`；data = `{"type","payload","ts"}`
            （payload 恒为对象、必带 runId、内禁 type 键名）。心跳：每 15s 发注释行 `:ping`。
            订阅：`?projectId=` / `?runId=` / `?workspaceId=` 过滤（与 payload 关联字段同名，
            可叠用 AND；缺省全量）；底座任务端点（POST /api/workspaces/{id}/agent/tasks）
            直发的事件带 workspaceId，projectId 自片5 业务编排桥接注入。

            名册（type → 说明，payload 除关联字段外）：

            | type | 类别 | payload 字段 |
            |---|---|---|
            | task-start | 平台 | runId, prompt, model, engine |
            | session-created | 平台 | runId, sessionId, engine |
            | error | 平台 | runId, message |
            | task-finish | 平台 | runId, sessionId, engine, finish |
            | text / reasoning / patch / tool / step-start / step-finish | 引擎透传 | … + `data`（引擎 part 原样） |

            名册正本与字段细则：docs/spec/SSE事件清单.md（新增顶层 type 先进清单再上线）。""")
    public SseEmitter subscribe(
            @Parameter(description = "按项目过滤（片5 业务桥接注入的字段；缺省不过滤）")
            @RequestParam(required = false) String projectId,
            @Parameter(description = "按运行过滤（任务进度页「看某个运行才挂」的常规姿势）")
            @RequestParam(required = false) String runId,
            @Parameter(description = "按工作区过滤（片2a 底座任务端点直发事件的关联字段）")
            @RequestParam(required = false) String workspaceId,
            @Parameter(in = ParameterIn.HEADER, description = "SSE 断线重连自动携带；"
                    + "无值（缺席或空串）= 新连接 = 先补发最近缓冲帧，有值 = 重连 = "
                    + "不补发（REST 重查兜底）")
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        // 新连/重连分野：空串视同无值（新连接）——浏览器只在真见过帧后才带非空值
        return appService.subscribe(projectId, runId, workspaceId,
                lastEventId == null || lastEventId.isBlank());
    }

    /**
     * SSE 断连的 async error dispatch 静默（本地优先于全局 advice）：向已死连接写帧
     * 失败经容器 async 机制 dispatch 回本端点，异常带原始业务栈（极易误读为业务 500）
     * ——连接已断且响应已提交，无事可做，不落 ERROR 噪音。负载下 complete() 与
     * dispatch 竞态会以 IllegalStateException（emitter 已完成）出现，一并静默。
     */
    @ExceptionHandler({IOException.class, IllegalStateException.class})
    public void handleDisconnectedClient() {
        // 空：SSE 是呈现通道，订阅方断开属正常生命周期（心跳/广播失败已逐出并 WARN）
    }
}
