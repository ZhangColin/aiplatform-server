package com.aieducenter.aiplatform.business.project.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.aieducenter.aiplatform.base.agentengine.application.AgentSessionAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentTaskAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentTaskDispatchCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentSessionResponse;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.business.task.application.event.TaskCompleted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TaskCompleted 回填续跑监听（A1 §3.1 第 3 步 / A4 §8，#27）：waitId 非空 →
 * 复用原 sessionId 续跑（prompt = summary——给会话的新消息，不是问答答复）；
 * 陈旧防护——等待点不可寻/会话已亡/项目无唯一归属 → 记日志跳过不抛（A1 §3.2）。
 */
@ExtendWith(MockitoExtension.class)
class TaskBackfillListenerTest {

    private static final long PROJECT_ID = 31L;
    private static final long WORKSPACE_ID = 3100L;
    private static final long ITERATION_ID = 3101L;
    private static final String WAIT_ID = "wait-31";
    private static final String SESSION_ID = "ses-origin";

    @Mock
    private AgentWaitAppService agentWaitAppService;

    @Mock
    private AgentSessionAppService agentSessionAppService;

    @Mock
    private AgentTaskAppService agentTaskAppService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private IterationRepository iterationRepository;

    @InjectMocks
    private TaskBackfillListener listener;

    @Test
    void given_wait_deferred_and_session_alive_when_completed_then_resume_with_summary() {
        stubWait(WaitOutcome.DEFERRED);
        stubSession("opencode", WORKSPACE_ID);
        stubProject();
        when(iterationRepository.findByProjectIdAndStatus(PROJECT_ID, IterationStatus.OPEN))
                .thenReturn(Optional.of(openIterationFixture()));

        listener.on(completed());

        // 复用原 sessionId 续跑：prompt = summary（新消息非问答答复）、引擎随会话行
        ArgumentCaptor<AgentTaskDispatchCommand> command =
                ArgumentCaptor.forClass(AgentTaskDispatchCommand.class);
        ArgumentCaptor<com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext>
                context = ArgumentCaptor.forClass(
                        com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext.class);
        verify(agentTaskAppService).dispatch(anyString(), command.capture(), context.capture());
        assertThat(command.getValue().sessionId()).isEqualTo(SESSION_ID);
        assertThat(command.getValue().engine()).isEqualTo("opencode");
        assertThat(command.getValue().prompt())
                .contains("处理结果")
                .contains("首轮回归报告")
                .contains("登录 500");
        // 计量归属：subject=projectId + dims role=RESUME + stage + iterationId（OPEN 期
        // 快照，A6 §3）；projectId 流关联注入
        assertThat(context.getValue().usageContext().subject())
                .isEqualTo(Long.toString(PROJECT_ID));
        assertThat(context.getValue().usageContext().dims())
                .containsEntry("role", TaskBackfillListener.RESUME_ROLE_DIM)
                .containsEntry("stage", ProjectMainChain.STAGE_BA)
                .containsEntry("iterationId", Long.toString(ITERATION_ID));
        assertThat(context.getValue().streamCorrelation())
                .containsEntry("projectId", Long.toString(PROJECT_ID));
    }

    @Test
    void given_no_open_iteration_when_completed_then_resume_dims_without_iteration_id() {
        // 期后回填续跑（A6 §3）：无 OPEN 期 → dims 不带 iterationId（归项目不归期）
        stubWait(WaitOutcome.DEFERRED);
        stubSession("opencode", WORKSPACE_ID);
        stubProject();
        when(iterationRepository.findByProjectIdAndStatus(PROJECT_ID, IterationStatus.OPEN))
                .thenReturn(Optional.empty());

        listener.on(completed());

        ArgumentCaptor<com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext>
                context = ArgumentCaptor.forClass(
                        com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext.class);
        verify(agentTaskAppService).dispatch(anyString(), any(), context.capture());
        assertThat(context.getValue().usageContext().dims())
                .containsEntry("role", TaskBackfillListener.RESUME_ROLE_DIM)
                .containsEntry("stage", ProjectMainChain.STAGE_CLOSED)
                .doesNotContainKey("iterationId");
    }

    @Test
    void given_task_without_wait_when_completed_then_no_resume() {
        listener.on(TaskCompleted.of("9", "TEST", "7", Instant.EPOCH, null, "报告"));

        verifyNoInteractions(agentTaskAppService); // 非转任务来源无回填
    }

    @Test
    void given_stale_backfill_when_completed_then_skipped_with_log() {
        // 等待点不可寻（已清理）
        when(agentWaitAppService.wait(WAIT_ID)).thenReturn(Optional.empty());
        listener.on(completed());

        // 会话已亡（会话表查无——环境/会话已死，A1 §3.2）
        stubWait(WaitOutcome.DEFERRED);
        when(agentSessionAppService.session(SESSION_ID)).thenReturn(Optional.empty());
        listener.on(completed());

        // 会话在工作区漂移（环境重建后 sessionId 撞名）：workspaceId 不匹配跳过
        stubSession("opencode", 9999L);
        listener.on(completed());

        verifyNoInteractions(agentTaskAppService);

        // 等待点非转任务关闭（例如后来被人工答了）：同样跳过
        stubWait(WaitOutcome.ANSWERED);
        listener.on(completed());
        verifyNoInteractions(agentTaskAppService);
    }

    @Test
    void given_workspace_without_unique_project_when_completed_then_skipped() {
        stubWait(WaitOutcome.DEFERRED);
        stubSession("opencode", WORKSPACE_ID);
        when(projectRepository.findByWorkspaceIdIn(List.of(WORKSPACE_ID)))
                .thenReturn(List.of()); // 无归属项目

        listener.on(completed());

        verifyNoInteractions(agentTaskAppService);
    }

    @Test
    void given_engine_failure_when_resume_then_swallowed_not_thrown() {
        stubWait(WaitOutcome.DEFERRED);
        stubSession("opencode", WORKSPACE_ID);
        stubProject();
        when(iterationRepository.findByProjectIdAndStatus(PROJECT_ID, IterationStatus.OPEN))
                .thenReturn(Optional.empty()); // 无 OPEN 期 → stage=CLOSED（期后回填）
        when(agentTaskAppService.dispatch(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("环境已销毁"));

        // 陈旧防护兜底：环境已亡等异常只记日志不抛（回填幂等友好）
        assertThatCode(() -> listener.on(completed())).doesNotThrowAnyException();
    }

    // ---------- 测试数据 ----------

    private TaskCompleted completed() {
        return TaskCompleted.of("9", "TEST", "7", Instant.EPOCH, WAIT_ID,
                "首轮回归报告\nBug 清单（1 条）：\n- [严重] 登录 500");
    }

    private void stubWait(WaitOutcome outcome) {
        when(agentWaitAppService.wait(WAIT_ID)).thenReturn(Optional.of(
                new WaitPointResponse(WAIT_ID, Long.toString(WORKSPACE_ID), SESSION_ID,
                        "run-origin", "que_1", WaitKind.QUESTION, null, WaitStatus.SETTLED,
                        null, "用哪个框架?", Map.of(), outcome, null, Instant.EPOCH,
                        Instant.EPOCH)));
    }

    private void stubSession(String engine, long workspaceId) {
        when(agentSessionAppService.session(SESSION_ID)).thenReturn(Optional.of(
                new AgentSessionResponse(SESSION_ID, Long.toString(workspaceId), engine,
                        "run-origin", null)));
    }

    /** 未持久化夹具的 id 由反射补上（Project.create 不落 id，落库才有）。 */
    private void stubProject() {
        Project project = Project.create("回填项目", ProjectType.WEBSITE, "opencode",
                WORKSPACE_ID, PROJECT_ID);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        when(projectRepository.findByWorkspaceIdIn(List.of(WORKSPACE_ID)))
                .thenReturn(List.of(project));
    }

    /** OPEN 期夹具（同 stubProject：id 反射补上，Iteration.open 不落 id）。 */
    private Iteration openIterationFixture() {
        Iteration iteration = Iteration.open(PROJECT_ID, Iteration.FIRST_SEQ,
                ProjectMainChain.STAGE_BA);
        ReflectionTestUtils.setField(iteration, "id", ITERATION_ID);
        return iteration;
    }
}
