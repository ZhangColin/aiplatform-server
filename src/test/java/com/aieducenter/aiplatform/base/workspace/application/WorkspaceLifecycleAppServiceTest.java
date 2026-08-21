package com.aieducenter.aiplatform.base.workspace.application;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.ProvisionedResource;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.cartisan.core.exception.ApplicationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作区生命周期用例（片1b 验收：mock 环境后端，聚焦编排、落库与事件时序）。
 * 生命周期三事件经 {@code @TransactionalEventListener(AFTER_COMMIT)} 的测试监听器
 * 捕获——A1 §5 片1 验收口径；监听器同时记录送达时的库内行数，证明「副作用真实
 * 落定后送达」不是事件自述。Docker 真实链路见 DockerEnvironmentBackendTest。
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

    /** 环境后端 mock：真容器链路在 DockerEnvironmentBackendTest，此处聚焦编排与事件。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

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
    void given_dev_command_when_create_then_recorded_and_created_event_after_commit() {
        when(environmentBackend.createWorkspace(any(), eq(EnvKind.DEV)))
                .thenReturn(devProvision("100"));

        WorkspaceResponse response = appService.create(new CreateWorkspaceCommand(EnvKind.DEV));

        assertThat(response.workspaceId()).isEqualTo("100");
        assertThat(response.kind()).isEqualTo(EnvKind.DEV);
        assertThat(response.resources()).hasSize(2);
        assertThat(response.resources())
                .extracting(WorkspaceResponse.MiddlewareResourceResponse::url)
                .containsExactly("postgresql://pg", "redis://rd");
        // 库记录真实落定（独立连接可见 = 已提交，服务重启后仍在）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT container_name FROM wsp_workspaces WHERE id = 100", String.class))
                .isEqualTo("ws-100-dev");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wsp_resources WHERE workspace_id = 100", Integer.class))
                .isEqualTo(2);
        // 事件 AFTER_COMMIT 送达，且送达时副作用已可查（非事务内自述）
        assertThat(eventRecorder.created()).hasSize(1);
        assertThat(eventRecorder.created().get(0).workspaceId().value()).isEqualTo("100");
        assertThat(eventRecorder.created().get(0).kind()).isEqualTo(EnvKind.DEV);
        assertThat(eventRecorder.workspacesAtCreatedDelivery()).isEqualTo(1);
    }

    @Test
    void given_default_command_when_create_then_dev_kind_passed_to_backend() {
        when(environmentBackend.createWorkspace(any(), any()))
                .thenReturn(devProvision("101"));

        appService.create(new CreateWorkspaceCommand(null));

        verify(environmentBackend).createWorkspace(any(), eq(EnvKind.DEV));
    }

    @Test
    void given_persist_fails_when_create_then_docker_resources_reclaimed_and_no_row() {
        // 预占同名容器：落库撞唯一约束 → 模拟记录失败路径
        workspaceRepository.save(Workspace.dev(WorkspaceId.of("999"),
                "ws-100-dev", "net-other", 1, 2));
        when(environmentBackend.createWorkspace(any(), eq(EnvKind.DEV)))
                .thenReturn(devProvision("100"));

        assertThatThrownBy(() -> appService.create(new CreateWorkspaceCommand(EnvKind.DEV)))
                .isInstanceOf(Exception.class);

        verify(environmentBackend).destroyWorkspace(any(WorkspaceHandle.class));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wsp_workspaces WHERE id = 100", Integer.class))
                .isEqualTo(0);
        assertThat(eventRecorder.created()).isEmpty();
    }

    @Test
    void given_backend_fails_when_create_then_propagated_and_nothing_recorded() {
        when(environmentBackend.createWorkspace(any(), any()))
                .thenThrow(new IllegalStateException("docker down"));

        assertThatThrownBy(() -> appService.create(new CreateWorkspaceCommand(EnvKind.DEV)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wsp_workspaces", Integer.class)).isEqualTo(0);
    }

    @Test
    void given_seeded_workspace_when_get_then_response_from_record() {
        workspaceRepository.save(Workspace.register(devProvision("102")));

        WorkspaceResponse response = appService.get("102");

        assertThat(response.containerName()).isEqualTo("ws-100-dev");
        assertThat(response.networkName()).isEqualTo("net-100");
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
