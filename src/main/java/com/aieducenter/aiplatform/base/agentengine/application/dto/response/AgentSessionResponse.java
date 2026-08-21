package com.aieducenter.aiplatform.base.agentengine.application.dto.response;

import java.time.LocalDateTime;

/**
 * agent 会话响应（按 workspaceId 寻址、重启后可查的验证面）。
 */
public record AgentSessionResponse(
        String sessionId,
        String workspaceId,
        String engine,
        String lastRunId,
        LocalDateTime createdAt) {
}
