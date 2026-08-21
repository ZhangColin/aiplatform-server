package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectWaitSettleCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectWaitResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 等待点桥接（片5a 验收第 3 步的编排面）：项目寻址 → 底座等待点通道（校验链/
 * deny cap 全在底座，AgentWaitAppServiceTest 覆盖）；settle 成功后发 SSE
 * wait-settled（projectId 桥接注入，outcome 映射）。
 */
@ExtendWith(MockitoExtension.class)
class ProjectWaitAppServiceTest {

    private static final long PROJECT_ID = 7L;
    private static final long WORKSPACE_ID = 77L;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AgentWaitAppService agentWaitAppService;

    @Mock
    private AgentStreamAppService streamAppService;

    @InjectMocks
    private ProjectWaitAppService appService;

    @Test
    void given_pending_waits_when_list_then_bridged_by_project_workspace() {
        stubProject();
        when(agentWaitAppService.pendingWaits(Long.toString(WORKSPACE_ID))).thenReturn(List.of(
                new WaitPointResponse("wait-1", Long.toString(WORKSPACE_ID), "ses-1", "run-1",
                        "que_1", WaitKind.QUESTION, WaitStatus.PENDING, "用哪个框架?",
                        Map.of("options", List.of("React")), null, null, null),
                new WaitPointResponse("wait-2", Long.toString(WORKSPACE_ID), "ses-1", "run-1",
                        "perm_1", WaitKind.PERMISSION, WaitStatus.PENDING, "允许写文件?",
                        Map.of(), null, null, null)));

        List<ProjectWaitResponse> waits = appService.pendingWaits(PROJECT_ID);

        assertThat(waits).hasSize(2);
        assertThat(waits.get(0).waitId()).isEqualTo("wait-1");
        assertThat(waits.get(0).kind()).isEqualTo(WaitKind.QUESTION);
        assertThat(waits.get(0).summary()).isEqualTo("用哪个框架?");
        assertThat(waits.get(1).kind()).isEqualTo(WaitKind.PERMISSION);
    }

    @Test
    void given_answer_settlement_when_settle_then_bridged_and_sse_outcome_answered() {
        stubProject();
        when(agentWaitAppService.wait("wait-1")).thenReturn(Optional.of(
                new WaitPointResponse("wait-1", Long.toString(WORKSPACE_ID), "ses-1", "run-1",
                        "que_1", WaitKind.QUESTION, WaitStatus.SETTLED, "用哪个框架?",
                        Map.of(), WaitOutcome.ANSWERED, null, null)));

        appService.settle(PROJECT_ID, "wait-1", new ProjectWaitSettleCommand(
                WaitSettleCommand.TYPE_ANSWER, List.of(List.of("React")), null, null));

        // 底座 settle：项目工作区寻址 + 三型命令映射
        verify(agentWaitAppService).settle(Long.toString(WORKSPACE_ID), "wait-1",
                new WaitSettleCommand(WaitSettleCommand.TYPE_ANSWER,
                        List.of(List.of("React")), null, null));
        // SSE wait-settled（副作用落定后发射）：projectId 桥接 + outcome 小写映射
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(streamAppService).publish(eq(AgentEventTypes.WAIT_SETTLED), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("projectId", Long.toString(PROJECT_ID))
                .containsEntry("runId", "run-1")
                .containsEntry("waitId", "wait-1")
                .containsEntry("outcome", "answered");
    }

    @Test
    void given_permission_deny_when_settle_then_sse_outcome_denied() {
        stubProject();
        when(agentWaitAppService.wait("wait-2")).thenReturn(Optional.of(
                new WaitPointResponse("wait-2", Long.toString(WORKSPACE_ID), "ses-1", "run-1",
                        "perm_1", WaitKind.PERMISSION, WaitStatus.SETTLED, "允许写文件?",
                        Map.of(), WaitOutcome.DENIED, null, null)));

        appService.settle(PROJECT_ID, "wait-2", new ProjectWaitSettleCommand(
                WaitSettleCommand.TYPE_PERMISSION, null, false, null));

        // deny cap / 平台终止在底座（AgentWaitAppServiceTest）；此处只断言桥接与 SSE
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(streamAppService).publish(eq(AgentEventTypes.WAIT_SETTLED), payload.capture());
        assertThat(payload.getValue()).containsEntry("outcome", "denied");
    }

    @Test
    void given_settle_without_outcome_when_settle_then_no_sse() {
        stubProject();
        when(agentWaitAppService.wait("wait-3")).thenReturn(Optional.of(
                new WaitPointResponse("wait-3", Long.toString(WORKSPACE_ID), "ses-1", "run-1",
                        "que_2", WaitKind.QUESTION, WaitStatus.PENDING, "用哪个框架?",
                        Map.of(), null, null, null)));

        appService.settle(PROJECT_ID, "wait-3", new ProjectWaitSettleCommand(
                WaitSettleCommand.TYPE_ANSWER, List.of(List.of("Vue")), null, null));

        // 无关闭结果（异常形态）不发射半成品事件
        verifyNoInteractions(streamAppService);
    }

    @Test
    void given_settle_when_wait_missing_after_settle_then_no_sse() {
        stubProject();
        when(agentWaitAppService.wait("wait-gone")).thenReturn(Optional.empty());

        appService.settle(PROJECT_ID, "wait-gone", new ProjectWaitSettleCommand(
                WaitSettleCommand.TYPE_DEFERRED, null, null, "转任务"));

        verify(agentWaitAppService).settle(anyString(), eq("wait-gone"), any());
        verifyNoInteractions(streamAppService);
    }

    @Test
    void given_missing_project_when_list_or_settle_then_prj_001() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appService.pendingWaits(PROJECT_ID))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
        assertThatThrownBy(() -> appService.settle(PROJECT_ID, "wait-1",
                new ProjectWaitSettleCommand(WaitSettleCommand.TYPE_ANSWER,
                        List.of(List.of("React")), null, null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
        verify(agentWaitAppService, never()).settle(anyString(), anyString(), any());
    }

    // ---------- 测试数据 ----------

    private void stubProject() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(
                Project.create("项目", ProjectType.WEBSITE, "opencode", WORKSPACE_ID, null)));
    }
}
