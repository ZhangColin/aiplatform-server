package com.aieducenter.aiplatform.base.workspace.domain.model;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性命名纯函数：containerName / networkName 是 workspaceId 的纯函数（与 docker
 * 后端同源派生，记录创建与置备不劈叉）。
 */
class WorkspaceNamingTest {

    @Test
    void given_workspace_id_when_container_name_then_kind_aware_deterministic() {
        assertThat(WorkspaceNaming.containerName(WorkspaceId.of("42"), EnvKind.DEV))
                .isEqualTo("ws-42-dev");
        assertThat(WorkspaceNaming.containerName(WorkspaceId.of("42"), EnvKind.TEST))
                .isEqualTo("ws-42-test");
        assertThat(WorkspaceNaming.containerName(WorkspaceId.of("42"), EnvKind.PROD))
                .isEqualTo("ws-42-prod");
    }

    @Test
    void given_workspace_id_when_network_name_then_deterministic() {
        assertThat(WorkspaceNaming.networkName(WorkspaceId.of("42"))).isEqualTo("net-42");
    }
}
