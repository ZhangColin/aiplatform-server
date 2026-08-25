package com.aieducenter.aiplatform.base.chatagent.domain.port;

import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentProgress;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

/**
 * 对话智能体驱动端口（#44）：平台进程内跑一轮多轮对话智能体（HarnessAgent）。
 * 流式文本经 progress 回调，结束返回汇聚回复；计量按命令的 usageContext 归属上报。
 */
@Port(PortType.CLIENT)
public interface ChatAgentClient {

    ChatAgentReply converse(ChatAgentCommand command, ChatAgentProgress progress);
}
