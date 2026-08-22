package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext;
import com.aieducenter.aiplatform.base.agentengine.application.AgentSessionAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentTaskAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentTaskDispatchCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentSessionResponse;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.business.task.application.event.TaskCompleted;

import lombok.extern.slf4j.Slf4j;

/**
 * TaskCompleted 回填续跑监听（A1 §3.1 第 3 步 / A4 §8，#27）——project 应用层
 * 是<b>唯一流程反应者</b>（docs/11 §7：task 只发布不编排）：任务带 waitId（转任务
 * 来源）→ 经工作区端口复用原 sessionId 续跑，prompt = summary——<b>续跑是给会话
 * 的新消息，不是问答答复</b>（demo replyQuestions 只收选项 label，装不下任务结果）。
 *
 * <p>陈旧防护（A1 §3.2）：等待点已不可寻 / 会话已亡（会话表查无）→ 记日志跳过
 * 不抛（回填幂等友好）；监听器幂等以任务状态机守门（仅 CONFIRMED 才发事件）。
 * 计量 dims role=RESUME 区分回填续跑用量；不处 role-assigned（无角色卡分配
 * 事实——会话沿袭原角色）与阶段计数（续跑是原任务的尾巴，不是新任务）。</p>
 */
@Component
@Slf4j
public class TaskBackfillListener {

    /** 回填续跑的计量角色维度值（与 FIX 同款用途标记）。 */
    public static final String RESUME_ROLE_DIM = "RESUME";

    private final AgentWaitAppService agentWaitAppService;
    private final AgentSessionAppService agentSessionAppService;
    private final AgentTaskAppService agentTaskAppService;
    private final ProjectRepository projectRepository;
    private final IterationRepository iterationRepository;

    public TaskBackfillListener(AgentWaitAppService agentWaitAppService,
                                AgentSessionAppService agentSessionAppService,
                                AgentTaskAppService agentTaskAppService,
                                ProjectRepository projectRepository,
                                IterationRepository iterationRepository) {
        this.agentWaitAppService = agentWaitAppService;
        this.agentSessionAppService = agentSessionAppService;
        this.agentTaskAppService = agentTaskAppService;
        this.projectRepository = projectRepository;
        this.iterationRepository = iterationRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TaskCompleted event) {
        if (event.waitId() == null || event.waitId().isBlank()) {
            return; // 非转任务来源——无回填续跑
        }
        try {
            resume(event);
        } catch (RuntimeException e) {
            // 陈旧防护兜底：环境已亡等一切异常只记日志，不抛（A1 §3.2）
            log.warn("[project] 任务 {} 回填续跑失败（waitId={}）：{}——跳过",
                    event.taskId(), event.waitId(), e.toString());
        }
    }

    // ---------- 内部 ----------

    private void resume(TaskCompleted event) {
        WaitPointResponse wait = agentWaitAppService.wait(event.waitId()).orElse(null);
        if (wait == null || wait.settleOutcome() != WaitOutcome.DEFERRED) {
            log.warn("[project] 任务 {} 的等待点 {} 不可寻或非转任务关闭，跳过回填",
                    event.taskId(), event.waitId());
            return;
        }
        AgentSessionResponse session = agentSessionAppService.session(wait.sessionId())
                .filter(alive -> alive.workspaceId().equals(wait.workspaceId()))
                .orElse(null);
        if (session == null) {
            log.warn("[project] 任务 {} 的原会话 {} 已亡，跳过回填（waitId={}）",
                    event.taskId(), wait.sessionId(), event.waitId());
            return;
        }
        List<Project> projects = projectRepository.findByWorkspaceIdIn(
                List.of(Long.parseLong(wait.workspaceId())));
        if (projects.size() != 1) {
            log.warn("[project] 等待点 {} 的工作区 {} 无唯一归属项目（{} 个），跳过回填",
                    event.waitId(), wait.workspaceId(), projects.size());
            return;
        }
        Project project = projects.get(0);
        Iteration openIteration = iterationRepository
                .findByProjectIdAndStatus(project.getId(), IterationStatus.OPEN)
                .orElse(null);
        String stage = openIteration != null
                ? openIteration.getStage() : ProjectMainChain.STAGE_CLOSED;

        agentTaskAppService.dispatch(
                Long.toString(project.getWorkspaceId()),
                new AgentTaskDispatchCommand(resumePromptOf(event), RolePreset.DEV.systemPrompt(),
                        RolePreset.DEV.modelId(), session.engine(), session.sessionId()),
                new AgentRunContext(AgentRunContext.newRunId(),
                        // 计量 dims 与 dispatchTask 同组装点（A6 §3：iterationId 有 OPEN
                        // 期才带——续跑归发起时所在期；期后续跑归项目不归期）
                        new UsageContext(Long.toString(project.getId()),
                                ProjectAgentTaskAppService.usageDims(RESUME_ROLE_DIM, stage,
                                        openIteration)),
                        Map.of(AgentStreamAppService.PROJECT_FIELD, Long.toString(project.getId()))));
        log.info("[project] 任务 {} 回填续跑：会话 {}（waitId={} summary 已作新消息）",
                event.taskId(), session.sessionId(), event.waitId());
    }

    /** 续跑 prompt：summary 是给会话的新消息（附一句来龙去脉，非问答答复）。 */
    private static String resumePromptOf(TaskCompleted event) {
        return "此前转任务的处理结果如下，请据此继续：\n\n" + event.summary();
    }
}
