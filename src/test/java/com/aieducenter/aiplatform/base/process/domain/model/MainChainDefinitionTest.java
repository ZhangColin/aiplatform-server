package com.aieducenter.aiplatform.base.process.domain.model;

import java.util.List;

import cn.hutool.core.collection.CollUtil;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.process.A3MainChainFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主链定义解析单测（票 #18 验收：A3 七步四门可作夹具；定义错误 fail fast）。
 */
class MainChainDefinitionTest {

    @Test
    void given_a3_main_chain_when_inspect_then_definition_preserved() {
        MainChainDefinition chain = A3MainChainFixture.mainChain();

        assertThat(chain.stages()).hasSize(6);
        assertThat(chain.first().name()).isEqualTo(A3MainChainFixture.REQUIREMENT);
        // A3 §2.2：测试/验收无默认角色；产物清单 v1 仅需求梳理段 PRD.md（§2.4）
        assertThat(chain.find(A3MainChainFixture.TEST).orElseThrow().defaultRole()).isNull();
        assertThat(chain.find(A3MainChainFixture.ACCEPTANCE).orElseThrow().defaultRole()).isNull();
        assertThat(chain.find(A3MainChainFixture.REQUIREMENT).orElseThrow().artifacts())
                .containsExactly("PRD.md");
        // 四扇门：需求确认 / Demo 确认 / 开发完成确认 / 验收；开发→测试无门
        long gated = chain.stages().stream().filter(stage -> stage.exitGate() != null).count();
        assertThat(gated).isEqualTo(4);
        assertThat(chain.find(A3MainChainFixture.DEVELOPMENT).orElseThrow().exitGate()).isNull();
        // 末位终态
        assertThat(chain.stages().getLast().terminal()).isTrue();
    }

    @Test
    void given_absent_name_when_find_then_empty() {
        MainChainDefinition chain = A3MainChainFixture.mainChain();

        assertThat(chain.find("NOPE")).isEmpty();
    }

    @Test
    void given_no_stages_when_construct_then_rejected() {
        assertThatThrownBy(() -> new MainChainDefinition(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("实阶段");
    }

    @Test
    void given_single_stage_without_terminal_when_construct_then_rejected() {
        StageEntry only = StageEntry.of("REQUIREMENT", "需求梳理", "BA", null,
                new ExitGate("用户", 1));

        assertThatThrownBy(() -> new MainChainDefinition(List.of(only)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("终态");
    }

    @Test
    void given_terminal_only_when_construct_then_rejected() {
        // 无实阶段的主链不是过程
        assertThatThrownBy(() -> new MainChainDefinition(
                List.of(StageEntry.terminalOf("DONE", "关闭"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("实阶段");
    }

    @Test
    void given_last_stage_not_terminal_when_construct_then_rejected() {
        List<StageEntry> stages = List.of(
                StageEntry.of("REQUIREMENT", "需求梳理", "BA", null, null),
                StageEntry.of("DEMO", "Demo", "DEMO", null, null));

        assertThatThrownBy(() -> new MainChainDefinition(stages))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("终态");
    }

    @Test
    void given_terminal_not_last_when_construct_then_rejected() {
        List<StageEntry> stages = List.of(
                StageEntry.terminalOf("DONE", "关闭"),
                StageEntry.of("REQUIREMENT", "需求梳理", "BA", null, null),
                StageEntry.terminalOf("DONE2", "关闭2"));

        assertThatThrownBy(() -> new MainChainDefinition(stages))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("终态");
    }

    @Test
    void given_duplicate_names_when_construct_then_rejected() {
        List<StageEntry> stages = List.of(
                StageEntry.of("REQUIREMENT", "需求梳理", "BA", null, null),
                StageEntry.of("REQUIREMENT", "需求梳理（二期）", "BA", null, null),
                StageEntry.terminalOf("DONE", "关闭"));

        assertThatThrownBy(() -> new MainChainDefinition(stages))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void given_mutable_source_list_when_construct_then_copied() {
        List<StageEntry> source = CollUtil.newArrayList(
                StageEntry.of("REQUIREMENT", "需求梳理", "BA", null, null),
                StageEntry.terminalOf("DONE", "关闭"));
        MainChainDefinition chain = new MainChainDefinition(source);

        source.clear();

        assertThat(chain.stages()).hasSize(2);
        assertThatThrownBy(() -> chain.stages().add(
                StageEntry.terminalOf("DONE", "关闭")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
