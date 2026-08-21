package com.aieducenter.aiplatform.base.process.domain.model;

/**
 * 出口门（A3 §2.2）：主链阶段出口的决策门——「谁拍板 + 计数门禁」。
 *
 * <p>门禁分层（A3 §2.4）：引擎只管计数（本阶段任务数 ≥ {@code minTasks} 才放行，
 * minTasks 按门可配——需求梳理/Demo/开发完成 = 1、验收 = 0）；业务谓词（如
 * G3 的「无未关闭 Bug」）归编排 approve 前校验，引擎不知业务内容。
 * {@code actor} 是拍板方标识（用户 / 开发平台），对引擎不透明。</p>
 *
 * @param actor    拍板方标识（非空白）
 * @param minTasks 计数门禁阈值：本阶段任务数下限（≥ 0；验收门 = 0 可表达）
 */
public record ExitGate(String actor, int minTasks) {

    public ExitGate {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("出口门的拍板方不能为空白");
        }
        if (minTasks < 0) {
            throw new IllegalArgumentException("出口门的最低任务数不能为负数：" + minTasks);
        }
    }

    /**
     * 计数门禁判定：本阶段任务数是否达标（≥ minTasks）。
     */
    public boolean satisfiedBy(int taskCount) {
        return taskCount >= minTasks;
    }
}
