package com.aieducenter.aiplatform.base.workspace.application;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.application.event.PreviewReady;
import com.aieducenter.aiplatform.base.workspace.application.event.WorkspaceCreated;
import com.aieducenter.aiplatform.base.workspace.application.event.WorkspaceDestroyed;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.ProvisionedResource;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作区生命周期用例（片1b 验收：mock 环境后端与后台置备器，聚焦编排、落库与事件时序）。
 * 创建异步化（#61）：创建即返回 PROVISIONING 记录 + WorkspaceCreated（AFTER_COMMIT），
 * docker 副作用转 {@link WorkspaceProvisionAppService} 后台（本测试 mock 置备器，只验「提交」；
 * 置备收敛在 WorkspaceProvisionAppServiceTest）。生命周期三事件经
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 测试监听器捕获；Docker 真实链路见
 * DockerEnvironmentBackendTest。
 */
@SpringBootTest
@Import(WorkspaceLifecycleAppServiceTest.EventRecorder.class)
class WorkspaceLifecycleAppServiceTest {

    @Autowired
    private WorkspaceLifecycleAppService appService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 环境后端 mock：create 不再同步调用它（#61），置备收敛在 WorkspaceProvisionAppServiceTest。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    /** 后台置备器 mock：编排只验「提交置备」的时机与入参，不验后台收敛。 */
    @MockitoBean
    private WorkspaceProvisionAppService provisioner;

    @Autowired
    private EventRecorder eventRecorder;

    @AfterEach
    void tearDown() {
        eventRecorder.clear();
        // 独立事务清场（断言失败时不留脏行）；资源行有 FK，先子后父
        jdbcTemplate.update("DELETE FROM wsp_resources");
        jdbcTemplate.update("DELETE FROM wsp_workspaces");
    }

    @Test
    void given_dev_command_when_create_then_provisioning_record_created_event_and_provision_enqueued() {
        WorkspaceResponse response = appService.create(new CreateWorkspaceCommand(EnvKind.DEV));

        // 创建即返回 PROVISIONING 记录：端口 0、资源清单空、确定性命名已落位
        assertThat(response.status()).isEqualTo(ProvisioningStatus.PROVISIONING);
        assertThat(response.hostPort()).isZero();
        assertThat(response.previewPort()).isZero();
        assertThat(response.resources()).isEmpty();
        assertThat(response.containerName()).isEqualTo("ws-" + response.workspaceId() + "-dev");
        assertThat(response.networkName()).isEqualTo("net-" + response.workspaceId());
        // 库记录真实落定（独立连接可见 = 已提交）：PROVISIONING 态
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provisioning_status FROM wsp_workspaces WHERE id = ?", Integer.class,
                Long.parseLong(response.workspaceId())))
                .isEqualTo(ProvisioningStatus.PROVISIONING.getCode());
        // 事件 AFTER_COMMIT 送达，workspaceId 即记录 id
        assertThat(eventRecorder.created()).hasSize(1);
        assertThat(eventRecorder.created().get(0).workspaceId().value())
                .isEqualTo(response.workspaceId());
        assertThat(eventRecorder.created().get(0).kind()).isEqualTo(EnvKind.DEV);
        assertThat(eventRecorder.workspacesAtCreatedDelivery()).isEqualTo(1);

        // 事务提交后提交后台置备（同 workspaceId）；本线程不同步调用 docker
        ArgumentCaptor<WorkspaceId> id = ArgumentCaptor.forClass(WorkspaceId.class);
        verify(provisioner).provision(id.capture(), eq(EnvKind.DEV));
        assertThat(id.getValue().value()).isEqualTo(response.workspaceId());
        verify(environmentBackend, never()).createWorkspace(any(), any());
    }

    @Test
    void given_default_command_when_create_then_dev_kind_passed_to_provisioner() {
        appService.create(new CreateWorkspaceCommand(null));

        verify(provisioner).provision(any(WorkspaceId.class), eq(EnvKind.DEV));
        verify(environmentBackend, never()).createWorkspace(any(), any());
    }

    @Test
    void given_seeded_workspace_when_get_then_response_from_record() {
        workspaceRepository.save(Workspace.register(devProvision("102")));

        WorkspaceResponse response = appService.get("102");

        assertThat(response.containerName()).isEqualTo("ws-100-dev");
        assertThat(response.networkName()).isEqualTo("net-100");
        assertThat(response.status()).isEqualTo(ProvisioningStatus.READY);
        assertThat(response.resources()).hasSize(2);
    }

    @Test
    void given_unknown_or_malformed_id_when_get_then_not_found() {
        assertThatThrownBy(() -> appService.get("404"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("工作区不存在");
        assertThatThrownBy(() -> appService.get("not-a-tsid"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("工作区不存在");
        // 非正数（TSID 恒正）语义上同不存在
        assertThatThrownBy(() -> appService.get("0"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("工作区不存在");
        assertThatThrownBy(() -> appService.get("-1"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("工作区不存在");
    }

    @Test
    void given_seeded_workspace_when_exec_then_handle_rebuilt_from_record() {
        workspaceRepository.save(Workspace.register(devProvision("103")));
        when(environmentBackend.exec(any(), eq("echo hi")))
                .thenReturn(new ExecResult("hi", "", 0));

        ExecResultResponse response = appService.exec("103", new WorkspaceExecCommand("echo hi"));

        assertThat(response.stdout()).isEqualTo("hi");
        assertThat(response.exitCode()).isZero();
        // 句柄从库记录重建（重启接回的执行面）：不是 create 时的那份内存对象
        ArgumentCaptor<WorkspaceHandle> handle = ArgumentCaptor.forClass(WorkspaceHandle.class);
        verify(environmentBackend).exec(handle.capture(), eq("echo hi"));
        assertThat(handle.getValue().containerName()).isEqualTo("ws-100-dev");
        assertThat(handle.getValue().previewPort()).isEqualTo(20001);
    }

    @Test
    void given_unknown_workspace_when_exec_then_not_found() {
        assertThatThrownBy(() -> appService.exec("404", new WorkspaceExecCommand("ls")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("工作区不存在");
    }

    @Test
    void given_pending_workspace_when_handleOf_then_deterministic_names_and_zero_ports() {
        workspaceRepository.save(Workspace.registerPending(WorkspaceId.of("106"), EnvKind.DEV));

        WorkspaceHandle handle = appService.handleOf("106");

        // 置备中句柄可取：确定性命名 + 端口 0（BA 对话只消费 containerName，无需等待）
        assertThat(handle.containerName()).isEqualTo("ws-106-dev");
        assertThat(handle.networkName()).isEqualTo("net-106");
        assertThat(handle.hostPort()).isZero();
        assertThat(handle.previewPort()).isZero();
    }

    @Test
    void given_pending_workspace_when_exec_then_implicitly_waits_until_ready() throws Exception {
        WorkspaceId id = WorkspaceId.of("106");
        workspaceRepository.save(Workspace.registerPending(id, EnvKind.DEV));
        when(environmentBackend.exec(any(), eq("echo hi"))).thenReturn(new ExecResult("hi", "", 0));

        // 后台线程延迟完成置备（模拟 provisioner 收敛 READY），主线程在 exec 处隐式等待
        Thread flipper = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            Workspace pending = workspaceRepository.findById(106L).orElseThrow();
            workspaceRepository.save(pending.complete(devProvision("106")));
        });
        flipper.start();

        ExecResultResponse response = appService.exec("106", new WorkspaceExecCommand("echo hi"));
        flipper.join();

        assertThat(response.stdout()).isEqualTo("hi");
        // exec 用的是就绪后回填真实端口的句柄（非置备中端口 0）
        ArgumentCaptor<WorkspaceHandle> handle = ArgumentCaptor.forClass(WorkspaceHandle.class);
        verify(environmentBackend).exec(handle.capture(), eq("echo hi"));
        assertThat(handle.getValue().previewPort()).isEqualTo(20001);
    }

    @Test
    void given_failed_workspace_when_exec_then_provision_failed_not_reaching_backend() {
        workspaceRepository.save(Workspace.registerPending(WorkspaceId.of("107"), EnvKind.DEV)
                .markFailed("WSP_008：docker 网络地址池已耗尽"));

        assertThatThrownBy(() -> appService.exec("107", new WorkspaceExecCommand("ls")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("环境置备失败");
        verify(environmentBackend, never()).exec(any(), any());
    }

    @Test
    void given_failed_workspace_when_get_then_provision_error_exposed() {
        workspaceRepository.save(Workspace.registerPending(WorkspaceId.of("108"), EnvKind.DEV)
                .markFailed("WSP_008：docker 网络地址池已耗尽"));

        WorkspaceResponse response = appService.get("108");

        assertThat(response.status()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(response.provisionError()).isEqualTo("WSP_008：docker 网络地址池已耗尽");
    }

    @Test
    void given_failed_workspace_when_retry_then_provisioning_and_reprovisioned() {
        workspaceRepository.save(Workspace.registerPending(WorkspaceId.of("109"), EnvKind.DEV)
                .markFailed("WSP_008：docker 网络地址池已耗尽"));

        WorkspaceResponse response = appService.retry("109");

        // FAILED → PROVISIONING（失败原因清空）落库，后台重新提交置备
        assertThat(response.status()).isEqualTo(ProvisioningStatus.PROVISIONING);
        assertThat(response.provisionError()).isNull();
        ArgumentCaptor<WorkspaceId> id = ArgumentCaptor.forClass(WorkspaceId.class);
        verify(provisioner).provision(id.capture(), eq(EnvKind.DEV));
        assertThat(id.getValue().value()).isEqualTo("109");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provisioning_status FROM wsp_workspaces WHERE id = 109", Integer.class))
                .isEqualTo(ProvisioningStatus.PROVISIONING.getCode());
    }

    @Test
    void given_ready_workspace_when_retry_then_state_invalid() {
        workspaceRepository.save(Workspace.register(devProvision("110")));

        assertThatThrownBy(() -> appService.retry("110"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("置备状态不合法");
        verify(provisioner, never()).provision(any(), any());
    }

    @Test
    void given_seeded_workspace_when_pack_source_then_bytes_from_backend_handle_rebuilt() {
        workspaceRepository.save(Workspace.register(devProvision("105")));
        byte[] tarball = {0x1f, (byte) 0x8b, 0x08};
        when(environmentBackend.packSource(any(WorkspaceHandle.class))).thenReturn(tarball);

        byte[] bytes = appService.packSource("105");

        // 源码包字节透传后端（真容器打包链路在 DockerEnvironmentBackendTest）
        assertThat(bytes).containsExactly(tarball);
        ArgumentCaptor<WorkspaceHandle> handle = ArgumentCaptor.forClass(WorkspaceHandle.class);
        verify(environmentBackend).packSource(handle.capture());
        assertThat(handle.getValue().containerName()).isEqualTo("ws-100-dev");
    }

    @Test
    void given_unknown_workspace_when_pack_source_then_not_found() {
        assertThatThrownBy(() -> appService.packSource("404"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("工作区不存在");
    }

    @Test
    void given_seeded_workspace_when_expose_preview_then_url_and_ready_event_after_commit() {
        workspaceRepository.save(Workspace.register(devProvision("104")));
        when(environmentBackend.exposePort(any(), eq(8081)))
                .thenReturn(URI.create("http://localhost:20001/"));

        URI url = appService.exposePreview("104");

        assertThat(url).isEqualTo(URI.create("http://localhost:20001/"));
        assertThat(eventRecorder.previewReady()).hasSize(1);
        assertThat(eventRecorder.previewReady().get(0).url())
                .isEqualTo(URI.create("http://localhost:20001/"));
        assertThat(eventRecorder.previewReady().get(0).workspaceId().value()).isEqualTo("104");
    }

    @Test
    void given_seeded_workspace_when_destroy_then_backend_cascade_then_records_deleted_and_event() {
        workspaceRepository.save(Workspace.register(devProvision("105")));

        appService.destroy("105");

        ArgumentCaptor<WorkspaceHandle> handle = ArgumentCaptor.forClass(WorkspaceHandle.class);
        verify(environmentBackend).destroyWorkspace(handle.capture());
        assertThat(handle.getValue().containerName()).isEqualTo("ws-100-dev");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wsp_workspaces WHERE id = 105", Integer.class))
                .isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wsp_resources WHERE workspace_id = 105", Integer.class))
                .isEqualTo(0);
        assertThat(eventRecorder.destroyed()).hasSize(1);
        assertThat(eventRecorder.destroyed().get(0).workspaceId().value()).isEqualTo("105");
        // AFTER_COMMIT：销毁事件送达时记录已删
        assertThat(eventRecorder.workspacesAtDestroyedDelivery()).isEqualTo(0);
    }

    @Test
    void given_pending_workspace_when_destroy_then_cancel_inflight_before_cascade() {
        workspaceRepository.save(Workspace.registerPending(WorkspaceId.of("111"), EnvKind.DEV));

        appService.destroy("111");

        // 置备中销毁（#64）：先取消在途后台置备（防 docker 侧完成后残留），再级联回收 + 删记录
        InOrder inOrder = inOrder(provisioner, environmentBackend);
        inOrder.verify(provisioner).cancel(WorkspaceId.of("111"));
        inOrder.verify(environmentBackend).destroyWorkspace(any(WorkspaceHandle.class));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wsp_workspaces WHERE id = 111", Integer.class))
                .isEqualTo(0);
    }

    @Test
    void given_unknown_workspace_when_destroy_then_not_found() {
        assertThatThrownBy(() -> appService.destroy("404"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("工作区不存在");
    }

    // ---------- 测试监听器（A1 §5：三事件捕获，AFTER_COMMIT 语义） ----------

    @TestConfiguration
    static class EventRecorder {

        private final List<WorkspaceCreated> created = new CopyOnWriteArrayList<>();
        private final List<WorkspaceDestroyed> destroyed = new CopyOnWriteArrayList<>();
        private final List<PreviewReady> previewReady = new CopyOnWriteArrayList<>();
        private volatile int workspacesAtCreatedDelivery = -1;
        private volatile int workspacesAtDestroyedDelivery = -1;

        private final JdbcTemplate jdbcTemplate;

        EventRecorder(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onCreated(WorkspaceCreated event) {
            workspacesAtCreatedDelivery = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM wsp_workspaces", Integer.class);
            created.add(event);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onDestroyed(WorkspaceDestroyed event) {
            workspacesAtDestroyedDelivery = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM wsp_workspaces", Integer.class);
            destroyed.add(event);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onPreviewReady(PreviewReady event) {
            previewReady.add(event);
        }

        List<WorkspaceCreated> created() {
            return created;
        }

        List<WorkspaceDestroyed> destroyed() {
            return destroyed;
        }

        List<PreviewReady> previewReady() {
            return previewReady;
        }

        int workspacesAtCreatedDelivery() {
            return workspacesAtCreatedDelivery;
        }

        int workspacesAtDestroyedDelivery() {
            return workspacesAtDestroyedDelivery;
        }

        void clear() {
            created.clear();
            destroyed.clear();
            previewReady.clear();
            workspacesAtCreatedDelivery = -1;
            workspacesAtDestroyedDelivery = -1;
        }
    }

    // ---------- 供给 fixture（containerName 固定，便于唯一约束冲突构造） ----------

    private WorkspaceProvision devProvision(String workspaceId) {
        WorkspaceHandle handle = WorkspaceHandle.dev(WorkspaceId.of(workspaceId),
                "ws-100-dev", "net-100", 20000, 20001);
        return new WorkspaceProvision(handle, List.of(
                new ProvisionedResource(MiddlewareKind.POSTGRESQL, "pg-100", 35432, "postgresql://pg"),
                new ProvisionedResource(MiddlewareKind.REDIS, "rd-100", 36379, "redis://rd")));
    }
}
