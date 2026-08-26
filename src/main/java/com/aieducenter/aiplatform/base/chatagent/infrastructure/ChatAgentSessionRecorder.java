package com.aieducenter.aiplatform.base.chatagent.infrastructure;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentengine.application.AgentSessionAppService;
import com.aieducenter.aiplatform.base.chatagent.application.ChatAgentAppService;

/**
 * 对话智能体会话登记收敛缝（#48）：session-created 发射口径的持久化判定 + settle
 * 可续跑的前置（agt_agent_sessions 有行且同工作区——AgentWaitAppService 的
 * requireResumableSession 硬依赖）。引擎值固定 {@code agentscope}（与等待点答复
 * 通道 WaitResponderDirectory 寻址键同值）；跨上下文经 agentengine 应用层登记
 * （照 {@link ChatAgentWorkspaceClient} 的收敛缝模式——chatagent 域不见他 BC
 * repository）。
 */
@Component
public class ChatAgentSessionRecorder {

    /** 对话智能体的引擎自述名（正本在 {@link ChatAgentAppService#ENGINE}，此处别名沿用）。 */
    public static final String ENGINE = ChatAgentAppService.ENGINE;

    private final AgentSessionAppService sessionAppService;

    public ChatAgentSessionRecorder(AgentSessionAppService sessionAppService) {
        this.sessionAppService = sessionAppService;
    }

    /**
     * 登记会话并回答「是否首见」（session-created 只在首见发，SSE事件清单；跨重启
     * 由会话行判定，不再进程内记忆）。已登记则刷新最近运行（ranOn）。
     */
    public boolean recordIfAbsent(String workspaceId, String sessionId, String runId) {
        return sessionAppService.recordIfAbsent(workspaceId, ENGINE, sessionId, runId);
    }
}
