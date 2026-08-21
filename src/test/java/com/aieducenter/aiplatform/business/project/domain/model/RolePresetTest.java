package com.aieducenter.aiplatform.business.project.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色卡 preset（六角色代码配置不落库，B0 §1 拆解：底座无角色概念，preset 是入参源）。
 */
class RolePresetTest {

    @Test
    void given_presets_when_inspect_then_six_roles_fully_configured() {
        assertThat(RolePreset.values()).hasSize(6);
        assertThat(RolePreset.values()).extracting(Enum::name)
                .containsExactlyInAnyOrder("BA", "DEV", "DELIVERY", "ARCH", "TEST", "DEMO");

        for (RolePreset role : RolePreset.values()) {
            assertThat(role.getName()).isNotBlank();
            assertThat(role.modelId()).isNotBlank();
            assertThat(role.systemPrompt()).isNotBlank();
        }
    }

    @Test
    void given_model_tier_when_inspect_then_dev_prothers_flash() {
        // demo 结论照收：文档类角色 flash（走链路优先），开发工程师 pro（吃推理）
        assertThat(RolePreset.DEV.modelId()).isEqualTo("deepseek-v4-pro");
        assertThat(RolePreset.BA.modelId()).isEqualTo("deepseek-v4-flash");
        assertThat(RolePreset.DEMO.modelId()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void given_name_when_by_name_then_resolved_or_empty() {
        assertThat(RolePreset.byName("DEV")).contains(RolePreset.DEV);
        assertThat(RolePreset.byName(" dev ")).contains(RolePreset.DEV);
        assertThat(RolePreset.byName("CODEX")).isEmpty();
        assertThat(RolePreset.byName(null)).isEmpty();
        assertThat(RolePreset.byName("")).isEmpty();
    }
}
