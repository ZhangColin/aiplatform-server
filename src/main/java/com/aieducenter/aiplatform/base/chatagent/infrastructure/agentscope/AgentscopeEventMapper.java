package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 事件映射表单点（#45 事件桥正本）：AgentScope 类型化事件 → 平台 agent 流事件帧
 * （SSE事件清单·通道二）。run 生命周期帧（task-start / session-created /
 * task-finish / error，平台封闭集合）经静态工厂构造；过程帧（引擎透传开放集合，
 * 清单已知名型）由 {@link #map} 逐事件产出——每帧 payload 盖 runId/sessionId/
 * engine（对齐编码引擎适配器口径），引擎侧细节藏 {@code data} 键内层。
 *
 * <p>映射表（未列类型跳过不产帧）：</p>
 * <table border="1">
 *   <caption>AgentScope 事件 → 平台流帧</caption>
 *   <tr><th>AgentScope 事件</th><th>平台 type</th><th>data 键</th></tr>
 *   <tr><td>TextBlockDelta</td><td>{@code text}</td><td>delta / blockId</td></tr>
 *   <tr><td>ThinkingBlockDelta</td><td>{@code reasoning}</td><td>delta / blockId</td></tr>
 *   <tr><td>ToolCallStart / ToolCallEnd</td><td>{@code tool}</td><td>toolCallId / toolName / phase</td></tr>
 *   <tr><td>ModelCallStart / ModelCallEnd</td><td>{@code step-start} / {@code step-finish}</td><td>replyId</td></tr>
 *   <tr><td>ExceedMaxIters</td><td>（结煞语）</td><td>{@link #finishToken}</td></tr>
 * </table>
 *
 * <p>HITL 类事件（RequireUserConfirm 等）不在本表——等待点双向桥归 #48。</p>
 */
final class AgentscopeEventMapper {

    // 引擎透传名型（SSE事件清单·通道二开放集合已知名型；本类是唯一引用点）
    private static final String TEXT = "text";
    private static final String REASONING = "reasoning";
    private static final String TOOL = "tool";
    private static final String STEP_START = "step-start";
    private static final String STEP_FINISH = "step-finish";

    /** ExceedMaxIters 的结煞语（task-finish.finish，对齐「引擎结煞语」口径）。 */
    private static final String FINISH_EXCEED_MAX_ITERS = "exceed_max_iters";
    private static final String FINISH_END = "end";

    private final String runId;
    private final String sessionId;
    private final String engine;

    AgentscopeEventMapper(String runId, String sessionId, String engine) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.engine = engine;
    }

    /** 过程事件 → 透传帧；未映射类型返回 {@code null}（跳过）。 */
    AgentEvent map(io.agentscope.core.event.AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta) {
            return passthrough(TEXT, Map.of(
                    "delta", nvl(delta.getDelta()),
                    "blockId", nvl(delta.getBlockId())));
        }
        if (event instanceof ThinkingBlockDeltaEvent delta) {
            return passthrough(REASONING, Map.of(
                    "delta", nvl(delta.getDelta()),
                    "blockId", nvl(delta.getBlockId())));
        }
        if (event instanceof ToolCallStartEvent start) {
            return passthrough(TOOL, Map.of(
                    "toolCallId", nvl(start.getToolCallId()),
                    "toolName", nvl(start.getToolCallName()),
                    "phase", "start"));
        }
        if (event instanceof ToolCallEndEvent end) {
            return passthrough(TOOL, Map.of(
                    "toolCallId", nvl(end.getToolCallId()),
                    "toolName", nvl(end.getToolCallName()),
                    "phase", "end"));
        }
        if (event instanceof ModelCallStartEvent start) {
            return passthrough(STEP_START, Map.of("replyId", nvl(start.getReplyId())));
        }
        if (event instanceof ModelCallEndEvent end) {
            return passthrough(STEP_FINISH, Map.of("replyId", nvl(end.getReplyId())));
        }
        return null;
    }

    /** 终态结煞语：流正常收尾时由调用方以默认 {@code end} 兜底。 */
    Optional<String> finishToken(io.agentscope.core.event.AgentEvent event) {
        if (event instanceof ExceedMaxItersEvent) {
            return Optional.of(FINISH_EXCEED_MAX_ITERS);
        }
        return Optional.empty();
    }

    // ---------- run 生命周期帧（平台封闭集合，对齐编码引擎适配器帧形状） ----------

    static AgentEvent taskStart(String runId, String prompt, String model, String engine) {
        return new AgentEvent(AgentEventTypes.TASK_START, Map.of(
                "runId", runId,
                "prompt", prompt,
                "model", model,
                "engine", engine));
    }

    static AgentEvent sessionCreated(String runId, String sessionId, String engine) {
        return new AgentEvent(AgentEventTypes.SESSION_CREATED, Map.of(
                "runId", runId,
                "sessionId", sessionId,
                "engine", engine));
    }

    static AgentEvent taskFinish(String runId, String sessionId, String finish, String engine) {
        return new AgentEvent(AgentEventTypes.TASK_FINISH, Map.of(
                "runId", runId,
                "sessionId", sessionId,
                "engine", engine,
                "finish", finish != null ? finish : FINISH_END));
    }

    static AgentEvent error(String runId, String message) {
        return new AgentEvent(AgentEventTypes.ERROR, Map.of(
                "runId", runId,
                "message", message != null ? message : "对话智能体运行失败"));
    }

    // ---------- 内部 ----------

    private AgentEvent passthrough(String type, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("sessionId", sessionId);
        payload.put("engine", engine);
        payload.put("data", data);
        return new AgentEvent(type, payload);
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
