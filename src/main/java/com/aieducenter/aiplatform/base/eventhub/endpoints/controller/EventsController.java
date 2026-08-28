package com.aieducenter.aiplatform.base.eventhub.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;

/**
 * 平台通知 SSE 通道（通道一，ADR-0001）：{@code GET /api/events}。
 *
 * <p>SSE 是呈现通道不是 REST——不套 ApiResponse，返回 {@link SseEmitter}；
 * 事件契约以名册正本（docs/spec/SSE事件清单.md）+ 本端点描述为准。</p>
 *
 * @since 0.1.0
 */
@RestController
@Validated
@RequestMapping("/api/events")
@Tag(name = "事件中心 SSE", description = "SSE 呈现通道（非 REST，不套 ApiResponse）；事件名册正本见 docs/spec/SSE事件清单.md")
public class EventsController {

    private final PlatformNotificationAppService appService;

    public EventsController(PlatformNotificationAppService appService) {
        this.appService = appService;
    }

    /**
     * 订阅平台通知事件流。
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅平台通知事件流（SSE）", description = """
            平台状态变化的广播（通道一）。事件只让 UI「活」，状态以 REST 重查为准，永不补发。

            信封：SSE name 恒为 `event`；id = `{projectId}:{seq}`；data = `{"type","payload","ts"}`
            （payload 恒为对象、必带 projectId、内禁 type 键名）。心跳：每 15s 发注释行 `:ping`
            （不进 listener，仅保活）。订阅：`?projectId=` 过滤（缺省全量）。

            名册（type → payload 字段）：

            | type | payload 字段 |
            |---|---|
            | workspace-created | projectId, projectName, container, projectType, engine |
            | stage-changed | projectId, stage, stageLabel, approved?, rejected?, reason? |
            | preview-ready | projectId, url |
            | workspace-destroyed | projectId |
            | task-updated | projectId, taskId, status |
            | document-updated | projectId, documentType |
            | project-renamed | projectId, projectName |

            名册正本与字段细则：docs/spec/SSE事件清单.md（新增顶层 type 先进清单再上线）。""")
    public SseEmitter subscribe(
            @Parameter(description = "按项目过滤（与信封关联字段同名；缺省 = 全量）")
            @RequestParam(required = false) String projectId) {
        return appService.subscribe(projectId);
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
