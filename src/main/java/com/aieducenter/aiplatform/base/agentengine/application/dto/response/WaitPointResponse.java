package com.aieducenter.aiplatform.base.agentengine.application.dto.response;

import java.time.Instant;
import java.util.Map;

import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentWait;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;

/**
 * 等待点响应（A1 §1.1 WaitPoint 形）：waitId 为业务层引用键（转任务/回填），
 * body 是引擎载荷原样（底座不解释，前端按 kind 自行取用）。枚举字段以
 * Integer code 序列化（BaseEnum 约定），xxxName 补显示名（枚举为 null 时同 null）。
 */
public record WaitPointResponse(
        String waitId,
        String workspaceId,
        String sessionId,
        String runId,
        String engineRef,
        WaitKind kind,
        String kindName,
        WaitStatus status,
        String statusName,
        String summary,
        Map<String, Object> body,
        WaitOutcome settleOutcome,
        String settleOutcomeName,
        Instant raisedAt,
        Instant settledAt) {

    public WaitPointResponse {
        kindName = kind == null ? null : kind.getName();
        statusName = status == null ? null : status.getName();
        settleOutcomeName = settleOutcome == null ? null : settleOutcome.getName();
    }

    /** 聚合 → 响应（应用层读面共用映射：*Name 由紧凑构造器从枚举派生，null 占位）。 */
    public static WaitPointResponse from(AgentWait wait) {
        return new WaitPointResponse(
                wait.getWaitId(),
                Long.toString(wait.getWorkspaceId()),
                wait.getSessionId(),
                wait.getRunId(),
                wait.getEngineRef(),
                wait.getKind(),
                null,
                wait.getStatus(),
                null,
                wait.getSummary(),
                wait.getBody(),
                wait.getSettleOutcome(),
                null,
                wait.getRaisedAt(),
                wait.getSettledAt());
    }
}
