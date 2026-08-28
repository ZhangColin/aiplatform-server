package com.aieducenter.aiplatform.base.workspace.application;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作区配置（#63）：置备最大尝试次数默认值 + 配置键真绑定（前缀/字段名拼写错时字段
 * 静默吃默认值，POJO setter 测不出来——照 #56 配置键实绑口径）。
 */
class WorkspacePropertiesTest {

    /** 规格值断言：默认最大尝试次数 3（含首次，即 2 次自动重试）。 */
    @Test
    void given_default_properties_when_get_provision_max_attempts_then_3() {
        assertThat(new WorkspaceProperties().getProvisionMaxAttempts()).isEqualTo(3);
    }

    /** 配置键真绑定（app.workspace.provision-max-attempts）。 */
    @Test
    void given_config_key_when_bind_then_provision_max_attempts_wired() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
                Map.of("app.workspace.provision-max-attempts", "5"));

        WorkspaceProperties bound = new Binder(source)
                .bind("app.workspace", Bindable.ofInstance(new WorkspaceProperties()))
                .get();

        assertThat(bound.getProvisionMaxAttempts()).isEqualTo(5);
    }
}
