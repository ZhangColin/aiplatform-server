package com.aieducenter.aiplatform.base.chatagent.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.port.ChatAgentClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 对话智能体用例（#45 平台接线）：{@link ChatAgentClient} 的流桥——适配器逐帧回调
 * 注入命令的流关联字段（如 projectId，对齐编码引擎 run 的 AgentRunContext 口径：
 * 底座不解释、逐帧透传）后经既有 agent 流通道（{@link AgentStreamAppService}，
 * runId 锚定）发射，前端零新增协议。converse 同步阻塞至本轮结束。
 *
 * <p>发射失败护栏：单帧发射异常只记日志不断流（SSE 是「让 UI 活」的面，不承担
 * 正确性，SSE事件清单）；对话本身的成败以 {@link ChatAgentReply} / 异常表达。</p>
 */
@Service
@Slf4j
public class ChatAgentAppService {

    private final ChatAgentClient chatAgentClient;
    private final AgentStreamAppService streamAppService;

    public ChatAgentAppService(ChatAgentClient chatAgentClient,
            AgentStreamAppService streamAppService) {
        this.chatAgentClient = chatAgentClient;
        this.streamAppService = streamAppService;
    }

    /**
     * 跑一轮对话：过程帧（task-start → 过程 → task-finish/error）实时进 agent 流
     * 通道（payload 已带 runId；关联字段随帧注入）。
     */
    public ChatAgentReply converse(ChatAgentCommand command) {
        return chatAgentClient.converse(command, sink(command.streamCorrelation()));
    }

    /** 流桥 sink：帧注入关联字段后发射既有通道；发射异常不拖垮对话。 */
    private Consumer<AgentEvent> sink(Map<String, Object> correlation) {
        return event -> {
            try {
                streamAppService.publish(event.type(),
                        withCorrelation(event.payload(), correlation));
            }
            catch (RuntimeException e) {
                log.warn("[chatagent] 流帧发射失败（{}）：{}", event.type(), e.getMessage());
            }
        };
    }

    /** 关联字段注入（透传不解释；帧序在前——寻址字段不覆盖帧本体字段）。 */
    private static Map<String, Object> withCorrelation(Map<String, Object> payload,
            Map<String, Object> correlation) {
        if (correlation == null || correlation.isEmpty()) {
            return payload;
        }
        Map<String, Object> addressed = new LinkedHashMap<>(correlation);
        addressed.putAll(payload);
        return addressed;
    }
}
