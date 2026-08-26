package com.aieducenter.aiplatform.base.chatagent.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.port.ChatAgentClient;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentWorkspaceClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 对话智能体用例（#45 平台接线，#48 等待点双向桥）：{@link ChatAgentClient} 的流桥
 * ——适配器逐帧回调注入命令的流关联字段（如 projectId，对齐编码引擎 run 的
 * AgentRunContext 口径：底座不解释、逐帧透传）后经既有 agent 流通道
 * （{@link AgentStreamAppService}，runId 锚定）发射，前端零新增协议。converse 同步
 * 阻塞至本轮结束（挂起轮在 wait-raised 后软终点返回——见 client 挂起语义）。
 *
 * <p><b>流桥拦截（#48，对齐 AgentTaskAppService.streamSink 语义）</b>：命令带
 * workspaceId 时——{@code wait-raised} 落库成平台等待点（raiseFromEvent，幂等），
 * 落库成功补 waitId 再发 SSE（挂起 REST 可查、SSE 可见）；run 终态
 * （task-finish/error，超时也是 error 表达）联动其 PENDING 等待点 → EXPIRED。
 * 落库/联动失败只记日志不拖垮流桥（护栏与编码引擎同款）。无 workspaceId（本地
 * 兜底）不拦截——纯透传（#45 既有口径）。{@link #sink} 公开供 settle 续跑桥
 * （resume 流同口径拦截：再挂起/终态联动）复用。</p>
 *
 * <p>发射失败护栏：单帧发射异常只记日志不断流（SSE 是「让 UI 活」的面，不承担
 * 正确性，SSE事件清单）；对话本身的成败以 {@link ChatAgentReply} / 异常表达。</p>
 */
@Service
@Slf4j
public class ChatAgentAppService {

    private final ChatAgentClient chatAgentClient;
    private final AgentStreamAppService streamAppService;
    private final AgentWaitAppService waitAppService;
    private final ChatAgentWorkspaceClient workspaceClient;

    public ChatAgentAppService(ChatAgentClient chatAgentClient,
            AgentStreamAppService streamAppService, AgentWaitAppService waitAppService,
            ChatAgentWorkspaceClient workspaceClient) {
        this.chatAgentClient = chatAgentClient;
        this.streamAppService = streamAppService;
        this.waitAppService = waitAppService;
        this.workspaceClient = workspaceClient;
    }

    /**
     * 跑一轮对话：过程帧（task-start → 过程 → task-finish/error；挂起轮在
     * wait-raised 后软终点）实时进 agent 流通道（payload 已带 runId；关联字段随帧
     * 注入）。
     */
    public ChatAgentReply converse(ChatAgentCommand command) {
        return chatAgentClient.converse(command,
                sink(command.workspaceId(), command.streamCorrelation()));
    }

    /** 流桥 sink：带 workspaceId 时拦截 wait-raised 落库与终态联动（见类注释）。 */
    public Consumer<AgentEvent> sink(String workspaceId, Map<String, Object> correlation) {
        boolean bridged = workspaceId != null && !workspaceId.isBlank();
        return event -> {
            try {
                if (bridged && AgentEventTypes.WAIT_RAISED.equals(event.type())) {
                    onWaitRaised(workspaceId, correlation, event);
                    return;
                }
                publish(event, withCorrelation(event.payload(), correlation));
                if (bridged && isRunTerminal(event)) {
                    expireRunOf(event);
                }
            }
            catch (RuntimeException e) {
                log.warn("[chatagent] 流帧处理失败（{}）：{}", event.type(), e.getMessage());
            }
        };
    }

    // ---------- 内部 ----------

    private void onWaitRaised(String workspaceId, Map<String, Object> correlation,
                              AgentEvent event) {
        WaitPointResponse raised = waitAppService.raiseFromEvent(
                workspaceClient.handleOf(workspaceId).workspaceId().id(), event.payload());
        // 落库即闭 + 关联方补发（带 waitId；发射归调用方关联，底座端点口径同编码引擎）
        Map<String, Object> addressed = withCorrelation(event.payload(), correlation);
        addressed.put(AgentEventTypes.WAIT_ID_FIELD, raised.waitId());
        streamAppService.publish(event.type(), addressed);
    }

    private boolean isRunTerminal(AgentEvent event) {
        return AgentEventTypes.TASK_FINISH.equals(event.type())
                || AgentEventTypes.ERROR.equals(event.type());
    }

    private void expireRunOf(AgentEvent event) {
        Object runId = event.payload().get(AgentStreamAppService.RUN_FIELD);
        if (runId != null) {
            waitAppService.expireRun(runId.toString());
        }
    }

    private void publish(AgentEvent event, Map<String, Object> payload) {
        try {
            streamAppService.publish(event.type(), payload);
        }
        catch (RuntimeException e) {
            log.warn("[chatagent] 流帧发射失败（{}）：{}", event.type(), e.getMessage());
        }
    }

    /** 关联字段注入（透传不解释；帧序在前——寻址字段不覆盖帧本体字段）。 */
    private static Map<String, Object> withCorrelation(Map<String, Object> payload,
                                                       Map<String, Object> correlation) {
        if (correlation == null || correlation.isEmpty()) {
            return new LinkedHashMap<>(payload);
        }
        Map<String, Object> addressed = new LinkedHashMap<>(correlation);
        addressed.putAll(payload);
        return addressed;
    }
}
