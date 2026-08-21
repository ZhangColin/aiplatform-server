package com.aieducenter.aiplatform.base.agentengine.application.dto.response;

import java.time.Instant;
import java.util.Map;

import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;

/**
 * 等待点响应（A1 §1.1 WaitPoint 形）：waitId 为业务层引用键（转任务/回填），
 * body 是引擎载荷原样（底座不解释，前端按 kind 自行取用）。
 */
public record WaitPointResponse(
        String waitId,
        String workspaceId,
        String sessionId,
        String runId,
        String engineRef,
        WaitKind kind,
        WaitStatus status,
        String summary,
        Map<String, Object> body,
        WaitOutcome settleOutcome,
        Instant raisedAt,
        Instant settledAt) {
}
