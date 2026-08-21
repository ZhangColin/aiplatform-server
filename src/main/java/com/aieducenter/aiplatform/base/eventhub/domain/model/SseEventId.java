package com.aieducenter.aiplatform.base.eventhub.domain.model;

/**
 * SSE 事件 id：{@code {streamId}:{seq}}（通知通道 streamId=projectId，agent 流通道
 * streamId=runId）。seq 在同一 streamId 内单调递增、从 1 起——补发（Last-Event-ID）
 * Phase A 不做，但 id 复合格式与单调性从第一天成立（B0 蓝图 §4① 吸收点）。
 *
 * @since 0.1.0
 */
public record SseEventId(String streamId, long seq) {

    public SseEventId {
        if (streamId == null || streamId.isBlank()) {
            throw new IllegalArgumentException("SSE 事件 id 的 streamId 不能为空");
        }
        if (seq <= 0) {
            throw new IllegalArgumentException("SSE 事件 id 的 seq 必须为正整数（从 1 起）");
        }
    }

    /**
     * 线格式值（SSE id: 行）。
     */
    public String value() {
        return streamId + ":" + seq;
    }
}
