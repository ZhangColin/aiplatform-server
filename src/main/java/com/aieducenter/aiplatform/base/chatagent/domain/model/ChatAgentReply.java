package com.aieducenter.aiplatform.base.chatagent.domain.model;

/**
 * 一轮对话的汇聚结果（#44）：最终文本（流式增量的汇聚，与回调拼接一致）。
 */
public record ChatAgentReply(String runId, String text) {
}
