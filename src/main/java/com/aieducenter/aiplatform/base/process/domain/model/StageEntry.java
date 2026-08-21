package com.aieducenter.aiplatform.base.process.domain.model;

import java.util.List;

/**
 * 主链阶段条目（A3 §2.2）：{@code {名/标签, 默认角色?, 产物清单?} + 出口门?}。
 *
 * <p>{@code defaultRole} 可空——测试/验收无默认角色（A3 §2.2）；{@code artifacts}
 * 可空且只作沉淀入库范围、不作门禁（A3 §2.4，v1 仅需求梳理段 PRD.md）；
 * {@code exitGate} 可空——无门阶段的推进由编排触发（A3 §2.3 开发→测试），
 * 引擎不做计数校验。{@code terminal} 标记主链终态（DONE）：终态条目居末且
 * 不得带门（推进无意义）。{@code name} 是稳定标识，持有方（business.project
 * 的期聚合）以其存储 / 寻址当前阶段。</p>
 *
 * @param name        稳定标识（非空白，主链内唯一）
 * @param label       展示标签（非空白）
 * @param defaultRole 默认角色（可空——测试/验收无）
 * @param artifacts   产物清单（可空，只作沉淀范围不作门禁）
 * @param exitGate    出口门（可空——无门段由编排触发推进）
 * @param terminal    是否主链终态（DONE）
 */
public record StageEntry(
        String name,
        String label,
        String defaultRole,
        List<String> artifacts,
        ExitGate exitGate,
        boolean terminal) {

    /**
     * 实阶段条目。
     */
    public static StageEntry of(String name, String label, String defaultRole,
            List<String> artifacts, ExitGate exitGate) {
        return new StageEntry(name, label, defaultRole, artifacts, exitGate, false);
    }

    /**
     * 终态条目（主链末尾 DONE）：无角色、无产物、无门。
     */
    public static StageEntry terminalOf(String name, String label) {
        return new StageEntry(name, label, null, null, null, true);
    }

    public StageEntry {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("阶段名不能为空白");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("阶段标签不能为空白：" + name);
        }
        if (terminal && exitGate != null) {
            throw new IllegalArgumentException("终态条目不得带出口门：" + name);
        }
        artifacts = artifacts == null ? null : List.copyOf(artifacts);
    }
}
