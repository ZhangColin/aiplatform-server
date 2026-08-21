package com.aieducenter.aiplatform.base.process.domain.service;

import java.util.List;

import com.cartisan.core.stereotype.DomainService;

import com.aieducenter.aiplatform.base.process.domain.model.AdvanceResult;
import com.aieducenter.aiplatform.base.process.domain.model.ExitGate;
import com.aieducenter.aiplatform.base.process.domain.model.MainChainDefinition;
import com.aieducenter.aiplatform.base.process.domain.model.StageEntry;

/**
 * 阶段推进引擎（B0 蓝图 §2 片4，票 #18）：无表纯逻辑——状态由持有方
 * （business.project 的期聚合，A3 修订）保存，每次调用以主链定义 + 当前阶段名 +
 * 本阶段任务数为入参。只管推进 / 驳回停留 / 门禁计数：不知业务内容、不知推进
 * 触发（门出口 = 人拍板、无门段 = 编排触发是编排约定，A3 §2.3）、不记确认留痕
 * （prj_confirmations 归业务）。
 */
@DomainService
public class StageAdvanceService {

    /**
     * 推进：门出口计数达标（任务数 ≥ minTasks）才放行；无门段自由推进（触发归
     * 编排）；末段推进即至终态（DONE）。
     *
     * @throws IllegalStateException 当前阶段不在主链定义中，或主链已收口
     * @throws IllegalArgumentException 任务数为负
     */
    public AdvanceResult advance(MainChainDefinition chain, String currentStage, int taskCount) {
        StageEntry current = liveStageOf(chain, currentStage);
        requireNonNegative(taskCount);

        ExitGate gate = current.exitGate();
        if (gate != null && !gate.satisfiedBy(taskCount)) {
            return new AdvanceResult.GateBlocked(gate, current, taskCount);
        }
        StageEntry next = chain.stages().get(indexOfStage(chain, currentStage) + 1);
        return new AdvanceResult.Advanced(next);
    }

    /**
     * 驳回：一律停留当前阶段（A3 §3——无「退回哪段」的问题），返回当前条目，
     * 迁移永不发生。
     *
     * @throws IllegalStateException 当前阶段不在主链定义中，或主链已收口
     */
    public StageEntry reject(MainChainDefinition chain, String currentStage) {
        return liveStageOf(chain, currentStage);
    }

    /**
     * 门就绪查询（A3 §5 gate.ready 的计数门禁半边；业务谓词由编排再 ∧）。
     * 无门段恒就绪（自由推进）。
     *
     * @throws IllegalStateException 当前阶段不在主链定义中，或主链已收口
     * @throws IllegalArgumentException 任务数为负
     */
    public boolean gateOpen(MainChainDefinition chain, String currentStage, int taskCount) {
        StageEntry current = liveStageOf(chain, currentStage);
        requireNonNegative(taskCount);

        ExitGate gate = current.exitGate();
        return gate == null || gate.satisfiedBy(taskCount);
    }

    private StageEntry liveStageOf(MainChainDefinition chain, String stageName) {
        StageEntry current = chain.find(stageName)
                .orElseThrow(() -> new IllegalStateException(
                        "主链定义中不存在阶段：" + stageName + "——持有方状态与主链定义不一致"));
        if (current.terminal()) {
            throw new IllegalStateException("主链已收口（DONE），阶段：" + stageName);
        }
        return current;
    }

    private int indexOfStage(MainChainDefinition chain, String stageName) {
        List<StageEntry> stages = chain.stages();
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).name().equals(stageName)) {
                return i;
            }
        }
        throw new IllegalStateException("主链定义中不存在阶段：" + stageName);
    }

    private void requireNonNegative(int taskCount) {
        if (taskCount < 0) {
            throw new IllegalArgumentException("本阶段任务数不能为负数：" + taskCount);
        }
    }
}
