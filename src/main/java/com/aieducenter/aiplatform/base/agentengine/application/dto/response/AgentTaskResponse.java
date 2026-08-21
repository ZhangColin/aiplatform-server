package com.aieducenter.aiplatform.base.agentengine.application.dto.response;

/**
 * 任务下发响应：runId 为本次运行的标识（任务端点生成，随响应返回，该运行全部流
 * 事件携带）；accepted=false 表示引擎未接单（失败原因经 agent 流 error 事件表达）。
 */
public record AgentTaskResponse(String runId, String sessionId, String engine, boolean accepted) {
}
