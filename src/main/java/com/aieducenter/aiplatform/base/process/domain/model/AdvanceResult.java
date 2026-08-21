package com.aieducenter.aiplatform.base.process.domain.model;

/**
 * 推进结果（纯值，无异常路径）：门禁不足是运行期业务拒绝，以值表达、由持有方
 * （business.project）翻译 409 {@code PRJ_}（A3 §2.4）——本上下文无错误码前缀。
 * 非法调用（未知阶段 / 已收口 / 负计数）是编程错误，仍抛
 * {@link IllegalStateException} / {@link IllegalArgumentException}。
 */
public sealed interface AdvanceResult {

    /**
     * 推进成功：迁入 {@code to}（末段推进时 {@code to} 为终态条目，持有方据此收口
     * ——期 CLOSED，A3 §2.2 验收门通过即收口）。
     */
    record Advanced(StageEntry to) implements AdvanceResult {
    }

    /**
     * 门禁不足拒绝：本阶段任务数未达出口门 minTasks，状态停留当前阶段。
     */
    record GateBlocked(ExitGate gate, StageEntry stage, int taskCount) implements AdvanceResult {
    }
}
