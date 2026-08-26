package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ToolUseBlock;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   <tr><td>RequireUserConfirm</td><td>{@code wait-raised}</td><td>{@link #waitRaised}（挂起帧，settle 续跑归 #48 双向桥）</td></tr>
 * </table>
 *
 * <p>HITL 挂起（{@code RequireUserConfirmEvent}）不是过程帧也不是终态：
 * {@link #map} 不产透传帧、{@link #finishToken} 无结煞语，由调用方以
 * {@link #waitRaised} 显式产帧发射（流桥落库成等待点）；挂起轮的收尾口径 =
 * 不发 task-finish（run 尚未终态，等 settle 续跑后再收口）。</p>
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

    /** 提问类工具（#48）：其确认挂起按 QUESTION 载荷形状呈现（向用户提问）。 */
    private static final String ASK_USER_TOOL = AskUserTool.NAME;

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

    /** 挂起检出（#48）：RequireUserConfirm = 本轮流挂起（软终点，非终态）。 */
    boolean isSuspension(io.agentscope.core.event.AgentEvent event) {
        return event instanceof RequireUserConfirmEvent;
    }

    /**
     * 挂起帧（#48 等待点双向桥 raise 侧）：{@code RequireUserConfirmEvent} →
     * {@code wait-raised}——payload 按 {@link AgentEventTypes} WAIT_* 契约（流桥落库
     * 成等待点）。kind 判定：待确认工具含提问类（ask_user，向用户提问）→ QUESTION
     * 载荷形状；其余（工具参数确认/敏感动作）→ PERMISSION。data = 引擎载荷原样：
     * toolCalls 待确认清单 + {@code resumeContext}（恢复私货——settle 侧据此重建
     * ConfirmResult 续跑：modelString/userId/usageContext/streamCorrelation）。
     *
     * <p>#40：QUESTION 的 data 增 {@code questions} 投影（header/question/multiple/
     * custom/options[{label}]，前端问答卡契约——multiple 恒 false / custom 恒 true：
     * ask_user 一次一题开放可自由输入；custom 必须显式 true，否则无选项题整题被
     * 前端收窄丢弃）。摘要口径与 opencode 问答对齐 = 问题文本（截断保短）。</p>
     */
    AgentEvent waitRaised(RequireUserConfirmEvent event, Map<String, Object> resumeContext) {
        List<ToolUseBlock> toolCalls = event.getToolCalls();
        List<ToolUseBlock> questions = toolCalls.stream()
                .filter(tc -> ASK_USER_TOOL.equals(tc.getName())).toList();
        boolean question = !questions.isEmpty();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", question ? "question" : "permission");
        data.put("toolCalls", toolCallPayloads(toolCalls));
        if (question) {
            data.put("questions", questionPayloads(questions));
        }
        data.putAll(resumeContext);
        return new AgentEvent(AgentEventTypes.WAIT_RAISED, Map.of(
                AgentEventTypes.WAIT_RUN_FIELD, runId,
                AgentEventTypes.WAIT_SESSION_FIELD, sessionId,
                "engine", engine,
                AgentEventTypes.WAIT_KIND_FIELD, question ? "QUESTION" : "PERMISSION",
                AgentEventTypes.WAIT_SUMMARY_FIELD, question
                        ? summaryOfQuestion(questions.get(0))
                        : summaryOf(toolCalls),
                AgentEventTypes.WAIT_ENGINE_REF_FIELD, nvl(event.getReplyId()),
                AgentEventTypes.WAIT_DATA_FIELD, data));
    }

    /**
     * 提问载荷投影（ask_user input → 前端问答卡形状）：header 缺省中性兜底；
     * options 字符串列表 → {label} 对象列表（无选项纯开放题也保留——custom=true
     * 恒可自由输入）。
     */
    private static List<Map<String, Object>> questionPayloads(List<ToolUseBlock> questions) {
        return questions.stream().map(tc -> {
            Map<String, Object> input = tc.getInput() != null ? tc.getInput() : Map.of();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("header", textOrDefault(input.get("header"), "提问"));
            payload.put("question", textOrDefault(input.get("question"), ""));
            payload.put("multiple", false);
            payload.put("custom", true);
            payload.put("options", optionPayloads(input.get("options")));
            return payload;
        }).toList();
    }

    private static List<Map<String, Object>> optionPayloads(Object options) {
        if (!(options instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(option -> Map.<String, Object>of("label", String.valueOf(option)))
                .toList();
    }

    /** 问答中性短文本：问题文本（截断保短，对齐 opencode questionSummary 口径）。 */
    private static String summaryOfQuestion(ToolUseBlock askUser) {
        Map<String, Object> input = askUser.getInput() != null ? askUser.getInput() : Map.of();
        String text = textOrDefault(input.get("question"), "");
        if (text.isBlank()) {
            return "智能体提问";
        }
        return text.length() > 100 ? text.substring(0, 100) : text;
    }

    private static String textOrDefault(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    /** 待确认工具清单载荷（id/name/input——ConfirmResult 重建所需的最小面）。 */
    private static List<Map<String, Object>> toolCallPayloads(List<ToolUseBlock> toolCalls) {
        return toolCalls.stream()
                .map(tc -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("id", nvl(tc.getId()));
                    payload.put("name", nvl(tc.getName()));
                    payload.put("input", tc.getInput() != null ? tc.getInput() : Map.of());
                    return payload;
                })
                .toList();
    }

    /** 挂起中性短文本：首个待确认工具名（多工具同挂时以首工具概览）。 */
    private static String summaryOf(List<ToolUseBlock> toolCalls) {
        return toolCalls.isEmpty() ? "" : nvl(toolCalls.get(0).getName());
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
