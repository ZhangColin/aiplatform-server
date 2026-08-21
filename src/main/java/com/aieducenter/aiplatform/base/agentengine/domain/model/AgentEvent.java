package com.aieducenter.aiplatform.base.agentengine.domain.model;

import java.util.Map;

/**
 * 一次运行内适配器向调用方回调的一个事件（CONTEXT.md「agent 流事件」的底座载体）。
 *
 * <p>两类（SSE事件清单·通道二）：平台事件（封闭集合：task-start / session-created /
 * error / task-finish，type 取 {@code AgentEventTypes}）与引擎透传事件（开放集合：
 * type = 引擎 part 类型原样，payload 的 {@code data} 键内为 part 原样）。</p>
 *
 * <p>适配器对每类事件都在 payload 内盖上 {@code runId}（+ 已知时的 {@code engine}/
 * {@code sessionId}）——runId 随 {@link AgentTaskCommand} 透传进适配器，任何 sink
 * （2a 任务端点的 SSE 桥 / 片5 业务编排桥）无需闭包即可关联。发射到通道的 payload
 * 顶层禁 {@code type} 键名（信封契约），引擎 part 的 type 藏在 {@code data} 内层。</p>
 */
public record AgentEvent(String type, Map<String, Object> payload) {

    public AgentEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("AgentEvent type 不能为空");
        }
        if (payload == null) {
            throw new IllegalArgumentException("AgentEvent payload 必须是对象");
        }
    }
}
