package com.aieducenter.aiplatform.base.workspace.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.base.workspace.domain.entity.MiddlewareResource;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.ProvisionedResource;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工作区聚合：注册不变量、资源登记幂等、句柄重建（重启接回的域内前提）。
 */
class WorkspaceTest {

    private static final WorkspaceId ID = WorkspaceId.of("42");

    @Test
    void given_valid_input_when_register_dev_then_workspace_created() {
        Workspace workspace = Workspace.register(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42-dev", "net-42", 20000, 20001)));

        assertThat(workspace.getKind()).isEqualTo(EnvKind.DEV);
        assertThat(workspace.getId()).isEqualTo(42L);
        assertThat(workspace.workspaceId()).isEqualTo(ID);
        assertThat(workspace.getContainerName()).isEqualTo("ws-42-dev");
        assertThat(workspace.getHostPort()).isEqualTo(20000);
        assertThat(workspace.getPreviewPort()).isEqualTo(20001);
    }

    @Test
    void given_runtime_kind_when_register_then_no_ports() {
        Workspace workspace = Workspace.register(WorkspaceProvision.of(
                WorkspaceHandle.runtime(ID, EnvKind.TEST, "ws-42-test", "net-42")));

        assertThat(workspace.getKind()).isEqualTo(EnvKind.TEST);
        assertThat(workspace.getHostPort()).isZero();
        assertThat(workspace.getPreviewPort()).isZero();
    }

    @Test
    void given_blank_fields_when_register_then_rejected() {
        assertThatThrownBy(() -> Workspace.register(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, " ", "net-42", 1, 2))))
                .isInstanceOf(DomainException.class);
        // 构造不变量各分支逐一（WSP_005）：空标识 / 空容器名 / 空网络名
        assertThatThrownBy(() -> Workspace.dev(null, "ws-42-dev", "net-42", 1, 2))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("工作区字段不完整");
        assertThatThrownBy(() -> Workspace.dev(ID, "ws-42-dev", " ", 1, 2))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Workspace.dev(ID, "ws-42-dev", null, 1, 2))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_dev_workspace_when_dev_factory_then_created() {
        // dev 显式工厂：带端口注册（与 register(dev 供给) 等价的直接路径）
        Workspace workspace = Workspace.dev(ID, "ws-42-dev", "net-42", 20000, 20001);

        assertThat(workspace.getKind()).isEqualTo(EnvKind.DEV);
    }

    @Test
    void given_dev_kind_when_runtime_factory_then_rejected() {
        assertThatThrownBy(() -> Workspace.runtime(ID, EnvKind.DEV, "c", "n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_same_kind_resource_when_register_twice_then_only_latest_kept() {
        Workspace workspace = Workspace.dev(ID, "ws-42-dev", "net-42", 20000, 20001);

        workspace.registerResource(new MiddlewareResource(42L, MiddlewareKind.POSTGRESQL,
                "pg-old", 5432, "postgresql://old"));
        workspace.registerResource(new MiddlewareResource(42L, MiddlewareKind.POSTGRESQL,
                "pg-new", 5433, "postgresql://new"));

        assertThat(workspace.getResources()).hasSize(1);
        assertThat(workspace.getResources().iterator().next().getContainerName()).isEqualTo("pg-new");
    }

    @Test
    void given_provision_with_resources_when_register_then_resources_attached() {
        WorkspaceProvision provision = WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42-dev", "net-42", 20000, 20001),
                new ProvisionedResource(MiddlewareKind.POSTGRESQL, "pg-42", 35432, "postgresql://pg"),
                new ProvisionedResource(MiddlewareKind.REDIS, "rd-42", 36379, "redis://rd"));

        Workspace workspace = Workspace.register(provision);

        assertThat(workspace.getResources()).hasSize(2);
        assertThat(workspace.getResources())
                .extracting(MiddlewareResource::getInternalUrl)
                .containsExactlyInAnyOrder("postgresql://pg", "redis://rd");
    }

    @Test
    void given_registered_workspace_when_to_handle_then_round_trip() {
        Workspace workspace = Workspace.register(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42-dev", "net-42", 20000, 20001)));

        WorkspaceHandle handle = workspace.toHandle();

        // 重启接回：记录 → 句柄无损重建（exec/销毁的寻址锚点）
        assertThat(handle.workspaceId()).isEqualTo(ID);
        assertThat(handle.kind()).isEqualTo(EnvKind.DEV);
        assertThat(handle.containerName()).isEqualTo("ws-42-dev");
        assertThat(handle.networkName()).isEqualTo("net-42");
        assertThat(handle.hostPort()).isEqualTo(20000);
        assertThat(handle.previewPort()).isEqualTo(20001);
    }
}
