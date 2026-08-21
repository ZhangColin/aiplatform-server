package com.aieducenter.aiplatform.base.process.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.process.A3MainChainFixture;
import com.aieducenter.aiplatform.base.process.domain.model.AdvanceResult;
import com.aieducenter.aiplatform.base.process.domain.model.MainChainDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段推进引擎单测（票 #18 验收：推进 / 驳回停留 / 门禁不足拒绝 / 主链末尾
 * DONE 终态 / minTasks 按门可配——验收 = 0）。夹具 = A3 七步四门（六段一终态）。
 */
class StageAdvanceServiceTest {

    private MainChainDefinition chain;
    private final StageAdvanceService engine = new StageAdvanceService();

    @BeforeEach
    void setUp() {
        chain = A3MainChainFixture.mainChain();
    }

    // ========== 推进 ==========

    @Test
    void given_gated_stage_with_count_met_when_advance_then_moves_to_next() {
        String next = advanceName(A3MainChainFixture.REQUIREMENT, 1);

        assertThat(next).isEqualTo(A3MainChainFixture.DEMO);
    }

    @Test
    void given_ungated_stage_when_advance_then_moves_without_counting() {
        // 开发→测试无门：推进由编排触发（首个测试任务创建，A3 §2.3），引擎不校验计数
        String next = advanceName(A3MainChainFixture.DEVELOPMENT, 0);

        assertThat(next).isEqualTo(A3MainChainFixture.TEST);
    }

    @Test
    void given_full_a3_chain_when_each_gate_satisfied_then_walk_reaches_done() {
        String position = advanceName(A3MainChainFixture.REQUIREMENT, 1);   // 需求确认（min=1）
        assertThat(position).isEqualTo(A3MainChainFixture.DEMO);
        position = advanceName(position, 1);   // Demo 确认（min=1）
        assertThat(position).isEqualTo(A3MainChainFixture.DEVELOPMENT);
        position = advanceName(position, 0);   // 无门：编排触发
        assertThat(position).isEqualTo(A3MainChainFixture.TEST);
        position = advanceName(position, 1);   // 开发完成确认（min=1）
        assertThat(position).isEqualTo(A3MainChainFixture.ACCEPTANCE);
        position = advanceName(position, 0);   // 验收（min=0）
        assertThat(position).isEqualTo(A3MainChainFixture.DONE);
        assertThat(chain.find(position).orElseThrow().terminal()).isTrue();
    }

    // ========== 门禁不足拒绝 ==========

    @Test
    void given_gated_stage_with_count_below_min_when_advance_then_blocked() {
        AdvanceResult result = engine.advance(chain, A3MainChainFixture.REQUIREMENT, 0);

        assertThat(result).isInstanceOf(AdvanceResult.GateBlocked.class);
        AdvanceResult.GateBlocked blocked = (AdvanceResult.GateBlocked) result;
        assertThat(blocked.gate().minTasks()).isEqualTo(1);
        assertThat(blocked.stage().name()).isEqualTo(A3MainChainFixture.REQUIREMENT);
        assertThat(blocked.taskCount()).isZero();
    }

    @Test
    void given_acceptance_gate_min_zero_when_advance_with_zero_tasks_then_moves() {
        // minTasks 按门可配：验收段 = 0 可表达（A3 §2.4）
        String next = advanceName(A3MainChainFixture.ACCEPTANCE, 0);

        assertThat(next).isEqualTo(A3MainChainFixture.DONE);
    }

    // ========== 驳回停留 ==========

    @Test
    void given_live_stage_when_reject_then_stays_at_current() {
        // 驳回一律停留当前阶段（A3 §3：无「退回哪段」的问题）
        assertThat(engine.reject(chain, A3MainChainFixture.ACCEPTANCE).name())
                .isEqualTo(A3MainChainFixture.ACCEPTANCE);
    }

    @Test
    void given_ungated_stage_when_reject_then_stays() {
        // 驳回不依赖门：任何实阶段都可驳回停留（如验收驳回后修复再验收）
        assertThat(engine.reject(chain, A3MainChainFixture.DEVELOPMENT).name())
                .isEqualTo(A3MainChainFixture.DEVELOPMENT);
    }

    // ========== 终态 ==========

    @Test
    void given_done_stage_when_advance_then_illegal() {
        assertThatThrownBy(() -> engine.advance(chain, A3MainChainFixture.DONE, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("收口");
    }

    @Test
    void given_done_stage_when_reject_then_illegal() {
        assertThatThrownBy(() -> engine.reject(chain, A3MainChainFixture.DONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("收口");
    }

    // ========== 非法入参 ==========

    @Test
    void given_unknown_stage_when_advance_then_illegal() {
        assertThatThrownBy(() -> engine.advance(chain, "NOPE", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    void given_unknown_stage_when_reject_then_illegal() {
        assertThatThrownBy(() -> engine.reject(chain, "NOPE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    void given_negative_task_count_when_advance_then_illegal() {
        assertThatThrownBy(() -> engine.advance(chain, A3MainChainFixture.REQUIREMENT, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务数");
    }

    // ========== 门就绪 ==========

    @Test
    void given_gate_min_one_when_gate_open_then_reflects_count() {
        assertThat(engine.gateOpen(chain, A3MainChainFixture.REQUIREMENT, 0)).isFalse();
        assertThat(engine.gateOpen(chain, A3MainChainFixture.REQUIREMENT, 1)).isTrue();
    }

    @Test
    void given_ungated_stage_when_gate_open_then_always_true() {
        assertThat(engine.gateOpen(chain, A3MainChainFixture.DEVELOPMENT, 0)).isTrue();
    }

    @Test
    void given_acceptance_gate_min_zero_when_gate_open_with_zero_then_true() {
        assertThat(engine.gateOpen(chain, A3MainChainFixture.ACCEPTANCE, 0)).isTrue();
    }

    @Test
    void given_unknown_stage_when_gate_open_then_illegal() {
        assertThatThrownBy(() -> engine.gateOpen(chain, "NOPE", 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOPE");
    }

    private String advanceName(String current, int taskCount) {
        AdvanceResult result = engine.advance(chain, current, taskCount);
        assertThat(result).isInstanceOf(AdvanceResult.Advanced.class);
        return ((AdvanceResult.Advanced) result).to().name();
    }
}
