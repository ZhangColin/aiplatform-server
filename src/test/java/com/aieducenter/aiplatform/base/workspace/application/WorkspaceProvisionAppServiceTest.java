package com.aieducenter.aiplatform.base.workspace.application;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.ProvisionedResource;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台置备器（#61 异步化核心）：驱动 {@link EnvironmentBackend} 后台置备，成功经
 * complete 回填端口 + 资源转 READY，失败转 FAILED（物理回收归后端内部级联回滚，见
 * DockerEnvironmentBackendTest）。直通执行器（{@code Runnable::run}）使异步提交同步化，
 * 聚焦状态机收敛；真实并发（多项目各自置备、互不串行）经生产线程池单独验收。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceProvisionAppServiceTest {

    @Mock
    private EnvironmentBackend environmentBackend;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Test
    void given_backend_success_when_provision_then_workspace_completed_to_ready() {
        WorkspaceId id = WorkspaceId.of("42");
        Workspace pending = Workspace.registerPending(id, EnvKind.DEV);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(pending));
        when(environmentBackend.createWorkspace(id, EnvKind.DEV)).thenReturn(devProvision(id));

        provisioner().provision(id, EnvKind.DEV);

        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ProvisioningStatus.READY);
        assertThat(saved.getValue().getHostPort()).isEqualTo(20000);
        assertThat(saved.getValue().getPreviewPort()).isEqualTo(20001);
        assertThat(saved.getValue().getResources()).hasSize(2);
    }

    @Test
    void given_backend_failure_when_provision_then_workspace_marked_failed_with_reason() {
        WorkspaceId id = WorkspaceId.of("42");
        Workspace pending = Workspace.registerPending(id, EnvKind.DEV);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(pending));
        when(environmentBackend.createWorkspace(id, EnvKind.DEV))
                .thenThrow(new ApplicationException(WorkspaceMessage.ENVIRONMENT_ADDRESS_POOL_EXHAUSTED));

        provisioner().provision(id, EnvKind.DEV);

        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        // 失败落归一化失败原因（WSP_008 自诊断，工作台可见）
        assertThat(saved.getValue().getProvisionError())
                .startsWith(WorkspaceMessage.ENVIRONMENT_ADDRESS_POOL_EXHAUSTED.code());
        // 失败不回填端口/资源（保持置备中占位，端口 0、清单空）
        assertThat(saved.getValue().getHostPort()).isZero();
        assertThat(saved.getValue().getResources()).isEmpty();
    }

    @Test
    void given_persistent_backend_failure_when_provision_then_retries_up_to_max_attempts() {
        WorkspaceId id = WorkspaceId.of("42");
        Workspace pending = Workspace.registerPending(id, EnvKind.DEV);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(pending));
        when(environmentBackend.createWorkspace(id, EnvKind.DEV))
                .thenThrow(new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                        "命令失败"));

        provisioner(3).provision(id, EnvKind.DEV);

        // 达上限转 failed：createWorkspace 被重试满 maxAttempts 次（含首次）
        verify(environmentBackend, times(3)).createWorkspace(id, EnvKind.DEV);
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ProvisioningStatus.FAILED);
    }

    @Test
    void given_transient_failure_when_provision_then_retries_then_ready() {
        WorkspaceId id = WorkspaceId.of("42");
        Workspace pending = Workspace.registerPending(id, EnvKind.DEV);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(pending));
        when(environmentBackend.createWorkspace(id, EnvKind.DEV))
                .thenThrow(new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                        "命令失败"))
                .thenReturn(devProvision(id));

        provisioner(3).provision(id, EnvKind.DEV);

        // 首次失败、第二次成功即收敛 READY，不继续重试
        verify(environmentBackend, times(2)).createWorkspace(id, EnvKind.DEV);
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ProvisioningStatus.READY);
        assertThat(saved.getValue().getProvisionError()).isNull();
    }

    @Test
    void given_workspace_deleted_when_provision_then_no_save() {
        // 置备在飞时记录已删（销毁竞争）：收敛静默跳过，不抛、不落库。
        // 注：此窗口 docker 侧资源已落定、记录已删——其级联回收（取消在途置备）归 #64
        WorkspaceId id = WorkspaceId.of("42");
        when(workspaceRepository.findById(42L)).thenReturn(Optional.empty());
        when(environmentBackend.createWorkspace(id, EnvKind.DEV)).thenReturn(devProvision(id));

        provisioner().provision(id, EnvKind.DEV);

        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void given_multiple_provisions_when_submitted_then_backend_driven_concurrently() throws Exception {
        // 生产线程池（4 线程）：N 个并发创建各自后台置备、互不串行阻塞——任一释放前
        // N 个已同时进入 docker 后端（串行实现则此 latch 永等不到 N）
        int n = 4;
        CountDownLatch entered = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        when(environmentBackend.createWorkspace(any(), eq(EnvKind.DEV))).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return devProvision(inv.getArgument(0));
        });

        WorkspaceProvisionAppService provisioner =
                new WorkspaceProvisionAppService(environmentBackend, workspaceRepository,
                        new WorkspaceProperties());
        try {
            for (int i = 0; i < n; i++) {
                provisioner.provision(new WorkspaceId(1000L + i), EnvKind.DEV);
            }
            assertThat(entered.await(10, TimeUnit.SECONDS))
                    .as("并发创建各自后台置备、互不串行阻塞")
                    .isTrue();
        } finally {
            release.countDown();
            provisioner.destroy();
        }
    }

    // ---------- 测试数据 ----------

    private WorkspaceProvisionAppService provisioner() {
        return provisioner(3);
    }

    private WorkspaceProvisionAppService provisioner(int maxAttempts) {
        return new WorkspaceProvisionAppService(environmentBackend, workspaceRepository,
                maxAttempts, Runnable::run);
    }

    private WorkspaceProvision devProvision(WorkspaceId id) {
        WorkspaceHandle handle = WorkspaceHandle.dev(id,
                "ws-" + id.value() + "-dev", "net-" + id.value(), 20000, 20001);
        return new WorkspaceProvision(handle, List.of(
                new ProvisionedResource(MiddlewareKind.POSTGRESQL, "pg-" + id.value(), 35432,
                        "postgresql://pg"),
                new ProvisionedResource(MiddlewareKind.REDIS, "rd-" + id.value(), 36379,
                        "redis://rd")));
    }
}
