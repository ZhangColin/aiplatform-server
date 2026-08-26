package com.aieducenter.aiplatform.base.chatagent.domain.port;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;
import java.util.function.Consumer;

/**
 * 对话智能体驱动端口（#44 建、#45 流桥化）：平台进程内跑一轮多轮对话智能体
 * （HarnessAgent）。过程事件（文本/思考增量、工具调用、轮次边界、终态）经
 * {@code sink} 以平台 agent 流事件帧逐个回调（runId 已锚定，映射口径正本见
 * {@code AgentscopeEventMapper}），同步阻塞至本轮结束返回汇聚回复；计量按命令的
 * usageContext 归属上报。
 */
@Port(PortType.CLIENT)
public interface ChatAgentClient {

    ChatAgentReply converse(ChatAgentCommand command, Consumer<AgentEvent> sink);
}
