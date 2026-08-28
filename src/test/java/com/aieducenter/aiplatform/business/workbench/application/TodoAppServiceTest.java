package com.aieducenter.aiplatform.business.workbench.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ProvisionFailedWorkspaceResponse;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.GateReadyResponse;
import com.aieducenter.aiplatform.business.task.application.TaskQueryAppService;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskTodoSource;
import com.aieducenter.aiplatform.business.workbench.application.dto.response.TodoItemResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 待办投影（票 #25 验收主面）：AGENT_WAIT（pending 等待点 → refId=waitId、
 * title 中性短文本、工作区无项目跳过）与 GATE_PENDING（门就绪 → refId=projectId）
 * 两型合并新者在前；view=dev|opc 过滤（opc v1 空——任务型随 A4）；不合法视角 400。
 */
@ExtendWith(MockitoExtension.class)
class TodoAppServiceTest {

    private static final Instant T1 = Instant.parse("2026-08-22T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-22T09:00:00Z");

    @Mock
    private AgentWaitAppService agentWaitAppService;

    @Mock
    private ProjectQueryAppService projectQueryAppService;

    @Mock
    private TaskQueryAppService taskQueryAppService;

    @Mock
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    private TodoAppService appService;

    @BeforeEach
    void setUp() {
        appService = new TodoAppService(agentWaitAppService, projectQueryAppService,
                taskQueryAppService, workspaceLifecycleAppService);
        // dev 各型缺省无置备失败（opc 不走该分支，lenient 免 UnnecessaryStubbing）
        lenient().when(workspaceLifecycleAppService.listProvisionFailed()).thenReturn(List.of());
    }

    // ---------- AGENT_WAIT ----------

    @Test
    void given_pending_wait_when_list_dev_then_agent_wait_todo_with_neutral_title() {
        when(agentWaitAppService.listPendingWaits()).thenReturn(List.of(
                questionWait("wait-1", "4242", T1),
                permissionWait("wait-2", "4243", T2)));
        when(projectQueryAppService.projectIdByWorkspaceId(any()))
                .thenReturn(Map.of(4242L, "p1", 4243L, "p2"));

        List<TodoItemResponse> todos = appService.list("dev");

        assertThat(todos).extracting(TodoItemResponse::type)
                .containsOnly(TodoItemResponse.TYPE_AGENT_WAIT);
        assertThat(todos).extracting(TodoItemResponse::refId)
                .containsExactly("wait-2", "wait-1"); // refId=waitId，新者在前
        // title 中性短文本（按种类，不透出智能体 summary）
        assertThat(todos).extracting(TodoItemResponse::title)
                .containsExactly("智能体等待权限批准", "智能体等待答复");
        assertThat(todos).extracting(TodoItemResponse::projectId)
                .containsExactly("p2", "p1");
        assertThat(todos).extracting(TodoItemResponse::createdAt)
                .containsExactly(T2, T1);
    }

    @Test
    void given_wait_without_project_when_list_dev_then_skipped() {
        // 工作区无归属项目（非 dev 环境等待点/项目已删残留）：不进 dev 待办
        when(agentWaitAppService.listPendingWaits())
                .thenReturn(List.of(questionWait("wait-x", "9999", T1)));
        when(projectQueryAppService.projectIdByWorkspaceId(any())).thenReturn(Map.of());

        assertThat(appService.list("dev")).isEmpty();
    }

    // ---------- GATE_PENDING ----------

    @Test
    void given_gate_ready_when_list_dev_then_gate_pending_todo_ref_id_project() {
        when(agentWaitAppService.listPendingWaits()).thenReturn(List.of());
        when(taskQueryAppService.submittedTodoSources()).thenReturn(List.of());
        when(taskQueryAppService.retestReadyProjects()).thenReturn(List.of());
        when(projectQueryAppService.listGateReady()).thenReturn(List.of(
                new GateReadyResponse("p9", "需求梳理", "user", T1)));

        List<TodoItemResponse> todos = appService.list("dev");

        assertThat(todos).hasSize(1);
        assertThat(todos.get(0).type()).isEqualTo(TodoItemResponse.TYPE_GATE_PENDING);
        assertThat(todos.get(0).projectId()).isEqualTo("p9");
        assertThat(todos.get(0).refId()).isEqualTo("p9"); // GATE_PENDING refId=projectId
        assertThat(todos.get(0).title()).isEqualTo("「需求梳理」门待拍板");
        assertThat(todos.get(0).createdAt()).isEqualTo(T1);
    }

    @Test
    void given_both_types_when_list_dev_then_merged_newest_first() {
        when(agentWaitAppService.listPendingWaits())
                .thenReturn(List.of(questionWait("wait-1", "4242", T2)));
        when(projectQueryAppService.projectIdByWorkspaceId(any()))
                .thenReturn(Map.of(4242L, "p1"));
        when(projectQueryAppService.listGateReady()).thenReturn(List.of(
                new GateReadyResponse("p9", "验收", "user", T1)));
        when(taskQueryAppService.submittedTodoSources()).thenReturn(List.of());
        when(taskQueryAppService.retestReadyProjects()).thenReturn(List.of());

        List<TodoItemResponse> todos = appService.list("dev");

        assertThat(todos).extracting(TodoItemResponse::type)
                .containsExactly(TodoItemResponse.TYPE_AGENT_WAIT,
                        TodoItemResponse.TYPE_GATE_PENDING); // 跨型合并，新者在前
    }

    // ---------- 任务型四型（A4 §7 接线） ----------

    @Test
    void given_submitted_task_when_list_dev_then_task_submitted_todo() {
        when(agentWaitAppService.listPendingWaits()).thenReturn(List.of());
        when(projectQueryAppService.listGateReady()).thenReturn(List.of());
        when(taskQueryAppService.submittedTodoSources()).thenReturn(List.of(
                new TaskTodoSource("t1", "p1", "回归测试", T1)));
        when(taskQueryAppService.retestReadyProjects()).thenReturn(List.of());

        List<TodoItemResponse> todos = appService.list("dev");

        assertThat(todos).hasSize(1);
        assertThat(todos.get(0).type()).isEqualTo(TodoItemResponse.TYPE_TASK_SUBMITTED);
        assertThat(todos.get(0).projectId()).isEqualTo("p1");
        assertThat(todos.get(0).refId()).isEqualTo("t1"); // 任务型 refId=taskId
        assertThat(todos.get(0).title()).isEqualTo("「回归测试」已提交，待确认");
        assertThat(todos.get(0).createdAt()).isEqualTo(T1);
    }

    @Test
    void given_retest_ready_project_when_list_dev_then_retest_todo_ref_id_project() {
        when(agentWaitAppService.listPendingWaits()).thenReturn(List.of());
        when(projectQueryAppService.listGateReady()).thenReturn(List.of());
        when(taskQueryAppService.submittedTodoSources()).thenReturn(List.of());
        when(taskQueryAppService.retestReadyProjects()).thenReturn(List.of(
                new TaskTodoSource(null, "p5", null, T2)));

        List<TodoItemResponse> todos = appService.list("dev");

        assertThat(todos).hasSize(1);
        assertThat(todos.get(0).type()).isEqualTo(TodoItemResponse.TYPE_RETEST_READY);
        assertThat(todos.get(0).refId()).isEqualTo("p5"); // RETEST_READY refId=projectId
        assertThat(todos.get(0).title()).isEqualTo("Bug 已修复，可发复测任务");
    }

    // ---------- WORKSPACE_PROVISION_FAILED ----------

    @Test
    void given_failed_workspace_when_list_dev_then_provision_failed_todo_ref_id_workspace() {
        when(agentWaitAppService.listPendingWaits()).thenReturn(List.of());
        when(projectQueryAppService.listGateReady()).thenReturn(List.of());
        when(taskQueryAppService.submittedTodoSources()).thenReturn(List.of());
        when(taskQueryAppService.retestReadyProjects()).thenReturn(List.of());
        when(workspaceLifecycleAppService.listProvisionFailed()).thenReturn(List.of(
                new ProvisionFailedWorkspaceResponse("4242", "WSP_008：docker 网络地址池已耗尽",
                        LocalDateTime.of(2026, 8, 22, 16, 0))));
        when(projectQueryAppService.projectIdByWorkspaceId(any()))
                .thenReturn(Map.of(4242L, "p1"));

        List<TodoItemResponse> todos = appService.list("dev");

        assertThat(todos).hasSize(1);
        assertThat(todos.get(0).type()).isEqualTo(TodoItemResponse.TYPE_WORKSPACE_PROVISION_FAILED);
        assertThat(todos.get(0).projectId()).isEqualTo("p1");
        assertThat(todos.get(0).refId()).isEqualTo("4242"); // refId=workspaceId
        assertThat(todos.get(0).title()).isEqualTo("环境置备失败");
    }

    @Test
    void given_failed_workspace_without_project_when_list_dev_then_skipped() {
        // 工作区无归属项目（项目已删残留）：不进 dev 待办
        when(workspaceLifecycleAppService.listProvisionFailed()).thenReturn(List.of(
                new ProvisionFailedWorkspaceResponse("9999", "WSP_008：docker 网络地址池已耗尽",
                        LocalDateTime.of(2026, 8, 22, 16, 0))));
        when(projectQueryAppService.projectIdByWorkspaceId(any())).thenReturn(Map.of());

        assertThat(appService.list("dev")).isEmpty();
    }

    // ---------- view 过滤 ----------

    @Test
    void given_opc_view_when_list_then_mine_types_only() throws Exception {
        // opc = NEW_TASK / TASK_REJECTED（assignee=me——会话上下文取账号）
        when(taskQueryAppService.publishedTodoSources(42L)).thenReturn(List.of(
                new TaskTodoSource("t1", "p1", "回归测试", T1)));
        when(taskQueryAppService.rejectedTodoSources(42L)).thenReturn(List.of());

        List<TodoItemResponse> todos = RequestContext.runFor(
                new RequestContext(null, null, null, null, 42L, "todo-test", null, null),
                () -> appService.list("opc"));

        assertThat(todos).extracting(TodoItemResponse::type)
                .containsOnly(TodoItemResponse.TYPE_NEW_TASK);
        assertThat(todos.get(0).refId()).isEqualTo("t1");
        assertThat(todos.get(0).title()).isEqualTo("「回归测试」新任务，待开始");
        verifyNoInteractions(agentWaitAppService, projectQueryAppService); // opc 不含 dev 两型
    }

    @Test
    void given_blank_view_when_list_then_defaults_to_dev() {
        when(agentWaitAppService.listPendingWaits()).thenReturn(List.of());
        when(projectQueryAppService.listGateReady()).thenReturn(List.of());
        when(taskQueryAppService.submittedTodoSources()).thenReturn(List.of());
        when(taskQueryAppService.retestReadyProjects()).thenReturn(List.of());

        assertThat(appService.list(null)).isEmpty();
        assertThat(appService.list("  ")).isEmpty();
    }

    @Test
    void given_unknown_view_when_list_then_bad_request() {
        // 无自有错误码前缀：入参问题走全局 BAD_REQUEST 面（与 settle 入参校验同口径）
        assertThatThrownBy(() -> appService.list("bogus"))
                .isInstanceOf(ApplicationException.class)
                .hasMessage("Invalid request");
    }

    // ---------- 测试数据 ----------

    private static WaitPointResponse questionWait(String waitId, String workspaceId,
                                                  Instant raisedAt) {
        return wait(waitId, workspaceId, WaitKind.QUESTION, raisedAt);
    }

    private static WaitPointResponse permissionWait(String waitId, String workspaceId,
                                                    Instant raisedAt) {
        return wait(waitId, workspaceId, WaitKind.PERMISSION, raisedAt);
    }

    private static WaitPointResponse wait(String waitId, String workspaceId, WaitKind kind,
                                          Instant raisedAt) {
        return new WaitPointResponse(waitId, workspaceId, "ses_1", "run-1", "ref_1",
                kind, null, WaitStatus.PENDING, null, "智能体产出的 summary", null,
                null, null, raisedAt, null);
    }
}
