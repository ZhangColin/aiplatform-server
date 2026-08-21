package com.aieducenter.aiplatform.base.process.domain.model;

import java.util.List;

import cn.hutool.core.collection.CollUtil;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段条目不变量单测：可选字段可空（角色/产物/门，A3 §2.2）、终态不得带门、
 * 产物清单防御性拷贝。
 */
class StageEntryTest {

    @Test
    void given_all_optional_fields_null_when_construct_then_allowed() {
        // 测试/验收段：无默认角色、无产物、无门
        StageEntry entry = StageEntry.of("TEST", "测试", null, null, null);

        assertThat(entry.defaultRole()).isNull();
        assertThat(entry.artifacts()).isNull();
        assertThat(entry.exitGate()).isNull();
        assertThat(entry.terminal()).isFalse();
    }

    @Test
    void given_blank_name_when_construct_then_rejected() {
        assertThatThrownBy(() -> StageEntry.of(" ", "测试", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("阶段名");
    }

    @Test
    void given_blank_label_when_construct_then_rejected() {
        assertThatThrownBy(() -> StageEntry.of("TEST", "", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标签");
    }

    @Test
    void given_terminal_entry_with_gate_when_construct_then_rejected() {
        // 终态无推进可言，带门是定义错误
        assertThatThrownBy(() -> new StageEntry("DONE", "关闭", null, null,
                new ExitGate("用户", 0), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("终态");
    }

    @Test
    void given_mutable_artifacts_source_when_construct_then_copied() {
        List<String> source = CollUtil.newArrayList("PRD.md");
        StageEntry entry = StageEntry.of("REQUIREMENT", "需求梳理", "BA", source, null);

        source.add("ARCH.md");

        assertThat(entry.artifacts()).containsExactly("PRD.md");
        assertThatThrownBy(() -> entry.artifacts().add("TEST.md"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void given_terminal_factory_when_construct_then_bare_terminal() {
        StageEntry done = StageEntry.terminalOf("DONE", "关闭");

        assertThat(done.terminal()).isTrue();
        assertThat(done.defaultRole()).isNull();
        assertThat(done.artifacts()).isNull();
        assertThat(done.exitGate()).isNull();
    }
}
