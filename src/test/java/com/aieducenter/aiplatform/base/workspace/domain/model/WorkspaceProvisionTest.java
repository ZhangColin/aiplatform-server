package com.aieducenter.aiplatform.base.workspace.domain.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 环境供给产出：句柄透传 + 资源清单空安全/防篡改 + 寻址便捷方法。
 */
class WorkspaceProvisionTest {

    @Test
    void given_null_resources_when_construct_then_empty_list() {
        WorkspaceProvision provision = new WorkspaceProvision(devHandle(), null);

        assertThat(provision.resources()).isEmpty();
    }

    @Test
    void given_resources_when_construct_then_unmodifiable() {
        WorkspaceProvision provision = WorkspaceProvision.of(devHandle(),
                new ProvisionedResource(MiddlewareKind.POSTGRESQL, "pg-1", 5432, "postgresql://pg"));

        assertThatThrownBy(() -> provision.resources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void given_provision_when_workspace_id_and_kind_then_delegated_to_handle() {
        WorkspaceProvision provision = new WorkspaceProvision(devHandle(), List.of());

        assertThat(provision.workspaceId().value()).isEqualTo("42");
        assertThat(provision.kind()).isEqualTo(EnvKind.DEV);
    }

    private WorkspaceHandle devHandle() {
        return WorkspaceHandle.dev(WorkspaceId.of("42"), "ws-42-dev", "net-42", 20000, 20001);
    }
}
