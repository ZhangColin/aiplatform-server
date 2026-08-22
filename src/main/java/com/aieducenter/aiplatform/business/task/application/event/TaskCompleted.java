package com.aieducenter.aiplatform.business.task.application.event;

import java.time.Instant;
import java.util.UUID;

import com.cartisan.event.ApplicationEvent;

/**
 * 任务已完成（A1 §3 / A4 §8）：dev 确认时刻在事务内经 PUBLISHER 端口发布，
 * 订阅方按 AFTER_COMMIT 语义在副作用真实落定后收到。幂等以状态机守门——
 * 仅 CONFIRMED 才发（重复确认被状态机拒绝）。驳回/取消不发。
 *
 * <p>订阅方（#27 回填续跑，business.project 唯一流程反应者）：waitId 非空 →
 * 复用原 sessionId 续跑（prompt = summary）。结算钩子 v1 不碰钱、无其他订阅方
 * （发布端就位原则，A1 同款）。</p>
 */
public record TaskCompleted(
        String eventId,
        Instant occurredAt,
        String taskId,
        String type,
        String assignee,
        Instant completedAt,
        String waitId,
        String summary) implements ApplicationEvent {

    public static TaskCompleted of(String taskId, String type, String assignee,
                                   Instant completedAt, String waitId, String summary) {
        return new TaskCompleted(UUID.randomUUID().toString(), Instant.now(), taskId, type,
                assignee, completedAt, waitId, summary);
    }

    @Override
    public String eventType() {
        return "TaskCompleted";
    }
}
