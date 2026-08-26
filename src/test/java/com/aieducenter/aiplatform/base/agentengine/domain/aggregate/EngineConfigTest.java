package com.aieducenter.aiplatform.base.agentengine.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 全局引擎配置聚合（票 #42）：单例行钉死 id=1（全平台一行，读写经应用服务不散落），
 * switchTo 只换 activeEngine——「新项目生效、存量不迁」的切换面（项目固化引擎
 * 不回头改）。空值不变量在聚合（守卫与 AgentSession 同款）。
 */
class EngineConfigTest {

    @Test
    void given_first_switch_when_global_then_singleton_row_pinned() {
        EngineConfig config = EngineConfig.global("dsh");

        assertThat(config.getId()).isEqualTo(EngineConfig.SINGLETON_ID);
        assertThat(config.getActiveEngine()).isEqualTo("dsh");
    }

    @Test
    void given_existing_row_when_switch_to_then_engine_replaced_in_place() {
        EngineConfig config = EngineConfig.global("opencode");

        config.switchTo("dsh");

        // 单行原地换值：不新增行（id 不变），审计字段随 Auditable 刷新
        assertThat(config.getId()).isEqualTo(EngineConfig.SINGLETON_ID);
        assertThat(config.getActiveEngine()).isEqualTo("dsh");
    }

    @Test
    void given_blank_engine_when_global_or_switch_then_invariant_rejected() {
        assertThatThrownBy(() -> EngineConfig.global(" "))
                .isInstanceOf(DomainException.class);
        EngineConfig config = EngineConfig.global("opencode");
        assertThatThrownBy(() -> config.switchTo(null))
                .isInstanceOf(DomainException.class);
        assertThat(config.getActiveEngine()).isEqualTo("opencode"); // 拒绝后不污染
    }
}
