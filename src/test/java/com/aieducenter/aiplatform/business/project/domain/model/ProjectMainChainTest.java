package com.aieducenter.aiplatform.business.project.domain.model;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.process.domain.model.MainChainDefinition;
import com.aieducenter.aiplatform.base.process.domain.model.StageEntry;

import com.aieducenter.aiplatform.business.project.domain.enums.ConfirmationKind;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主链定义（A3 §2.2 唯一一条：八段序四扇门 + 角色可空 + 产物仅 PRD.md + 终态关闭）。
 */
class ProjectMainChainTest {

    private final MainChainDefinition chain = ProjectMainChain.definition();

    @Test
    void given_chain_when_inspect_order_then_ba_first_closed_terminal_last() {
        assertThat(chain.first().name()).isEqualTo(ProjectMainChain.STAGE_BA);

        assertThat(chain.stages()).extracting(StageEntry::name).containsExactly(
                ProjectMainChain.STAGE_BA, ProjectMainChain.STAGE_DEMO, ProjectMainChain.STAGE_DEV,
                ProjectMainChain.STAGE_TEST, ProjectMainChain.STAGE_ACCEPTANCE,
                ProjectMainChain.STAGE_CLOSED);
        assertThat(chain.stages()).extracting(StageEntry::terminal)
                .containsOnly(false, false, false, false, false, true);
    }

    @Test
    void given_chain_when_inspect_roles_then_test_and_acceptance_have_none() {
        assertThat(chain.find(ProjectMainChain.STAGE_BA).orElseThrow().defaultRole())
                .isEqualTo(RolePreset.BA.name());
        assertThat(chain.find(ProjectMainChain.STAGE_DEMO).orElseThrow().defaultRole())
                .isEqualTo(RolePreset.DEMO.name());
        assertThat(chain.find(ProjectMainChain.STAGE_DEV).orElseThrow().defaultRole())
                .isEqualTo(RolePreset.DEV.name());
        // A3 §2.2：测试/验收无默认角色；ARCH/DELIVERY 不设段（开发期手动下任务）
        assertThat(chain.find(ProjectMainChain.STAGE_TEST).orElseThrow().defaultRole()).isNull();
        assertThat(chain.find(ProjectMainChain.STAGE_ACCEPTANCE).orElseThrow().defaultRole())
                .isNull();
        assertThat(chain.stages()).extracting(StageEntry::name)
                .doesNotContain(RolePreset.ARCH.name(), RolePreset.DELIVERY.name());
    }

    @Test
    void given_chain_when_inspect_gates_then_four_gates_and_dev_gateless() {
        // 四扇门：G1/G2 用户、G3 开发平台、G4 用户；minTasks 验收段 = 0（A3 §2.4）
        assertThat(chain.find(ProjectMainChain.STAGE_BA).orElseThrow().exitGate())
                .isEqualTo(new com.aieducenter.aiplatform.base.process.domain.model.ExitGate(
                        ProjectMainChain.GATE_ACTOR_USER, 1));
        assertThat(chain.find(ProjectMainChain.STAGE_TEST).orElseThrow().exitGate())
                .isEqualTo(new com.aieducenter.aiplatform.base.process.domain.model.ExitGate(
                        ProjectMainChain.GATE_ACTOR_PLATFORM, 1));
        assertThat(chain.find(ProjectMainChain.STAGE_ACCEPTANCE).orElseThrow().exitGate())
                .isEqualTo(new com.aieducenter.aiplatform.base.process.domain.model.ExitGate(
                        ProjectMainChain.GATE_ACTOR_USER, 0));
        // 开发段无门：开发→测试由编排触发（A3 §2.3，A4 首个测试任务）
        assertThat(chain.find(ProjectMainChain.STAGE_DEV).orElseThrow().exitGate()).isNull();
    }

    @Test
    void given_chain_when_inspect_artifacts_then_only_ba_carries_prd() {
        // #41 grilling 定案：PRD 住工作区 docs/（读写方共用 PRD_ARTIFACT 单一事实）
        assertThat(chain.find(ProjectMainChain.STAGE_BA).orElseThrow().artifacts())
                .containsExactly(ProjectMainChain.PRD_ARTIFACT);
        assertThat(chain.find(ProjectMainChain.STAGE_DEMO).orElseThrow().artifacts()).isNull();
        assertThat(chain.find(ProjectMainChain.STAGE_TEST).orElseThrow().artifacts()).isNull();
    }

    @Test
    void given_gated_stages_when_confirmation_kind_then_matched_one_by_one() {
        // A3 §3：四扇门与确认种类一一对应（测试段的门叫「开发完成确认」）
        assertThat(ProjectMainChain.confirmationKindOf(ProjectMainChain.STAGE_BA))
                .contains(ConfirmationKind.REQUIREMENT);
        assertThat(ProjectMainChain.confirmationKindOf(ProjectMainChain.STAGE_DEMO))
                .contains(ConfirmationKind.DEMO);
        assertThat(ProjectMainChain.confirmationKindOf(ProjectMainChain.STAGE_TEST))
                .contains(ConfirmationKind.DEVELOPMENT);
        assertThat(ProjectMainChain.confirmationKindOf(ProjectMainChain.STAGE_ACCEPTANCE))
                .contains(ConfirmationKind.ACCEPTANCE);
    }

    @Test
    void given_gate_less_or_terminal_stage_when_confirmation_kind_then_empty() {
        // 开发段无门（推进归编排触发）、终态无门（收口后再无确认）
        assertThat(ProjectMainChain.confirmationKindOf(ProjectMainChain.STAGE_DEV)).isEmpty();
        assertThat(ProjectMainChain.confirmationKindOf(ProjectMainChain.STAGE_CLOSED)).isEmpty();
        assertThat(ProjectMainChain.confirmationKindOf(null)).isEmpty();
    }
}
