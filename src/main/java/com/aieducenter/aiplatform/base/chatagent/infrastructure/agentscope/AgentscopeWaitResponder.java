package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitQueryAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.port.WaitResponder;
import com.aieducenter.aiplatform.base.chatagent.application.ChatAgentAppService;
import com.aieducenter.aiplatform.base.chatagent.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentSessionRecorder;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope.AgentscopeChatAgentClient.ChatAgentResume;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 对话智能体的等待点答复通道（#48 双向桥 settle 侧）：实现 agentengine 窄面
 * {@link WaitResponder}（engine = agentscope，经 WaitResponderDirectory 寻址——不进
 * 编码引擎能力矩阵，ADR-0002 双轨分野）。settle 派发到达时：按 (sessionId,
 * engineRef=replyId) 读等待点（settle 顺序先引擎后落库，此刻 PENDING 行在）→
 * body 的恢复私货重建待确认工具与续跑入参 → ConfirmResult 批复 → 经
 * {@link ChatAgentResumeGate} 异步续跑（回复即时返回，引擎交互不阻塞 settle 落库）。
 *
 * <p>跨重启口径：恢复私货（toolCalls/modelString/userId/计量归属/流关联）全在等待点
 * body 落库——进程重启后 settle 同样可续跑；对话上下文（访谈历史）在
 * PostgresAgentStateStore 的 (userId, sessionId) 槽位。</p>
 */
@Component
@Adapter(PortType.CLIENT)
@Slf4j
public class AgentscopeWaitResponder implements WaitResponder {

    private static final String ANSWER_DELIMITER = "、";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentWaitQueryAppService waitQueryService;
    private final AgentscopeChatAgentClient client;
    /** 续跑 sink（AppService 流桥）运行期才解析——构造期直依会与 settle 派发目录成环
     * （directory → 本类 → AppService → AgentWaitAppService → directory）。 */
    private final ObjectProvider<ChatAgentAppService> appServiceProvider;
    private final ChatAgentResumeGate gate;

    @Autowired
    public AgentscopeWaitResponder(AgentWaitQueryAppService waitQueryService,
                                   AgentscopeChatAgentClient client,
                                   ObjectProvider<ChatAgentAppService> appServiceProvider,
                                   ChatAgentResumeGate gate) {
        this.waitQueryService = waitQueryService;
        this.client = client;
        this.appServiceProvider = appServiceProvider;
        this.gate = gate;
    }

    /** 测试便利构造（受控执行器 + 直持 AppService）。 */
    AgentscopeWaitResponder(AgentWaitQueryAppService waitQueryService,
                            AgentscopeChatAgentClient client,
                            ChatAgentAppService appService, Executor executor) {
        this(waitQueryService, client, providerOf(appService), new ChatAgentResumeGate(executor));
    }

    private static ObjectProvider<ChatAgentAppService> providerOf(
            ChatAgentAppService appService) {
        return new ObjectProvider<>() {
            @Override
            public ChatAgentAppService getObject() {
                return appService;
            }
        };
    }

    @Override
    public String engine() {
        return ChatAgentSessionRecorder.ENGINE;
    }

    @Override
    public void replyQuestions(WorkspaceHandle handle, String sessionId, String requestId,
                               List<List<String>> answers) {
        // QUESTION（向用户提问）：答复文本注入工具 input 的 answer 键（提问工具执行
        // 即读它作为工具结果）+ 进恢复消息文本（LLM 上下文直接可读）
        String answerText = String.join(ANSWER_DELIMITER,
                answers.stream().flatMap(List::stream).toList());
        resumeFromWait(sessionId, requestId, answerText,
                toolCall -> withAnswer(toolCall, answerText));
    }

    @Override
    public void replyPermission(WorkspaceHandle handle, String sessionId, String permissionId,
                                boolean approve) {
        // PERMISSION（工具参数确认/敏感动作）：批复直译——approve/deny 全批生效
        resumeFromWait(sessionId, permissionId, approve ? "approved" : "denied",
                toolCall -> new ConfirmResult(approve, toolCall));
    }

    @Override
    public boolean abort(WorkspaceHandle handle, String sessionId) {
        // deny cap 平台终止路径：关闸取消在飞/排队续跑（与编码引擎 abort 同口径——
        // 无运行可终止也返回 true：对话智能体挂起时无在飞流，闸即全部运行面）
        gate.close(sessionId);
        return true;
    }

    // ---------- 内部 ----------

    private void resumeFromWait(String sessionId, String engineRef, String resumeText,
                                Function<ToolUseBlock, ConfirmResult> decision) {
        WaitPointResponse wait = waitQueryService.pendingByRef(sessionId, engineRef)
                .orElseThrow(() -> new IllegalStateException(
                        "等待点不存在或已非 PENDING：" + sessionId + "/" + engineRef));
        Map<String, Object> data = wait.body() != null ? wait.body() : Map.of();
        List<ToolUseBlock> toolCalls = toolCallsOf(data);
        if (toolCalls.isEmpty()) {
            throw new IllegalStateException(
                    "等待点 body 缺待确认工具清单：" + sessionId + "/" + engineRef);
        }
        List<ConfirmResult> confirmResults = new ArrayList<>(toolCalls.size());
        for (ToolUseBlock toolCall : toolCalls) {
            confirmResults.add(decision.apply(toolCall));
        }
        Map<String, Object> correlation = mapValue(data, "streamCorrelation");
        ChatAgentResume resume = new ChatAgentResume(
                wait.runId(), sessionId, blankToNull(textValue(data, "userId")),
                wait.workspaceId(), blankToNull(textValue(data, "modelString")),
                blankToNull(textValue(data, "systemPrompt")),
                engineRef, confirmResults, resumeText,
                usageContextOf(data), correlation);
        boolean submitted = gate.submit(sessionId, () -> client.resume(resume,
                appServiceProvider.getObject().sink(wait.workspaceId(), correlation)));
        log.info("[chatagent] 等待点答复派发续跑 session={} ref={} submitted={}",
                sessionId, engineRef, submitted);
    }

    /** 待确认工具重建（body.toolCalls = 挂起帧写入的 id/name/input 最小面）。 */
    private static List<ToolUseBlock> toolCallsOf(Map<String, Object> data) {
        Object raw = data.get("toolCalls");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ToolUseBlock> toolCalls = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> input = new LinkedHashMap<>();
            Object inputRaw = map.get("input");
            if (inputRaw instanceof Map<?, ?> inputMap) {
                inputMap.forEach((k, v) -> input.put(String.valueOf(k), v));
            }
            toolCalls.add(askingToolCall(
                    String.valueOf(map.get("id")), String.valueOf(map.get("name")), input));
        }
        return toolCalls;
    }

    /**
     * 重建为 ASKING 态（与会话状态同形——确认应用按此替换，否则原 ASKING 残留卡后续轮）。
     * content 回填 input 的 JSON 串：重放的参数校验（ToolValidator.validateInput）只认
     * content 原文不认 input map，null 会让重放炸「argument "content" is null」错误结果
     * 给模型（#51——模型见错换 id 重问/自述提问系统失败）。
     */
    private static ToolUseBlock askingToolCall(String id, String name,
                                               Map<String, Object> input) {
        return new ToolUseBlock(id, name, input, toJson(input), null,
                io.agentscope.core.message.ToolCallState.ASKING);
    }

    private static String toJson(Map<String, Object> input) {
        try {
            return JSON.writeValueAsString(input);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("待确认工具参数序列化失败", e);
        }
    }

    /** 提问批复：答复注入工具 input（ConfirmResult 允许携带修改后的 toolCall）。 */
    private static ConfirmResult withAnswer(ToolUseBlock toolCall, String answerText) {
        Map<String, Object> input = new LinkedHashMap<>(toolCall.getInput() != null
                ? toolCall.getInput() : Map.of());
        input.put("answer", answerText);
        return new ConfirmResult(true, askingToolCall(
                toolCall.getId(), toolCall.getName(), input));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String textValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    private static UsageContext usageContextOf(Map<String, Object> data) {
        Map<String, Object> raw = mapValue(data, "usageContext");
        if (raw.isEmpty()) {
            return null;
        }
        Map<String, String> dims = new LinkedHashMap<>();
        mapValue(raw, "dims").forEach((k, v) -> dims.put(k, String.valueOf(v)));
        return new UsageContext(String.valueOf(raw.get("subject")), dims);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
