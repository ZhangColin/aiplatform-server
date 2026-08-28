package com.aieducenter.aiplatform.base.workspace.application;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 环境就绪等待（#62 文件操作级隐式等待）：READY 即回、PROVISIONING 轮询至 READY、
 * FAILED 抛失败、超时抛超时、等待中删除抛 404。mock 仓储返回状态序列 + 短超时/间隔，
 * 确定性验收收敛逻辑，不测真实时钟。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceReadinessWaiterTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Test
    void given_ready_workspace_when_awaitReady_then_returned_without_repository_call() {
        Workspace ready = ready(id("42"));

        Workspace result = waiter().awaitReady(ready);

        assertThat(result).isSameAs(ready);
        verify(workspaceRepository, never()).findById(anyLong());
    }

    @Test
    void given_provisioning_workspace_when_awaitReady_then_polls_until_ready() {
        Workspace pending = pending(id("42"));
        Workspace ready = ready(id("42"));
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(pending), Optional.of(ready));

        Workspace result = waiter(Duration.ofSeconds(1), Duration.ofMillis(1)).awaitReady(pending);

        assertThat(result.getStatus()).isEqualTo(ProvisioningStatus.READY);
        assertThat(result.getHostPort()).isEqualTo(20000);
        assertThat(result.getPreviewPort()).isEqualTo(20001);
    }

    @Test
    void given_failed_workspace_when_awaitReady_then_provision_failed() {
        Workspace failed = failed(id("42"));

        assertThatThrownBy(() -> waiter().awaitReady(failed))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("环境置备失败");
        verify(workspaceRepository, never()).findById(anyLong());
    }

    @Test
    void given_workspace_becomes_failed_when_awaitReady_then_provision_failed() {
        Workspace pending = pending(id("42"));
        when(workspaceRepository.findById(42L))
                .thenReturn(Optional.of(pending), Optional.of(failed(id("42"))));

        assertThatThrownBy(() -> waiter(Duration.ofSeconds(1), Duration.ofMillis(1)).awaitReady(pending))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("环境置备失败");
    }

    @Test
    void given_still_provisioning_when_awaitReady_then_timeout_not_silent_hang() {
        Workspace pending = pending(id("42"));
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> waiter(Duration.ofMillis(30), Duration.ofMillis(5)).awaitReady(pending))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("环境置备等待超时");
    }

    @Test
    void given_workspace_deleted_when_awaitReady_then_not_found() {
        Workspace pending = pending(id("42"));
        when(workspaceRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> waiter(Duration.ofSeconds(1), Duration.ofMillis(1)).awaitReady(pending))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("工作区不存在");
    }

    // ---------- 测试数据 ----------

    private WorkspaceReadinessWaiter waiter() {
        return new WorkspaceReadinessWaiter(workspaceRepository,
                Duration.ofSeconds(1), Duration.ofMillis(1));
    }

    private WorkspaceReadinessWaiter waiter(Duration timeout, Duration interval) {
        return new WorkspaceReadinessWaiter(workspaceRepository, timeout, interval);
    }

    private Workspace pending(WorkspaceId id) {
        return Workspace.registerPending(id, EnvKind.DEV);
    }

    private Workspace ready(WorkspaceId id) {
        return pending(id).complete(devProvision(id));
    }

    private Workspace failed(WorkspaceId id) {
        return pending(id).markFailed("WSP_008：docker 网络地址池已耗尽");
    }

    private WorkspaceProvision devProvision(WorkspaceId id) {
        WorkspaceHandle handle = WorkspaceHandle.dev(id,
                "ws-" + id.value() + "-dev", "net-" + id.value(), 20000, 20001);
        return new WorkspaceProvision(handle, List.of());
    }

    private WorkspaceId id(String value) {
        return WorkspaceId.of(value);
    }
}
