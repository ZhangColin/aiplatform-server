package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext;
import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitQueryAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.chatagent.application.ChatAgentAppService;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.UsageContext;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectWaitSettleCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectAgentTaskResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * BA 访谈编排（#40 访谈循环本体，ADR-0002 双轨分野）：BA 是平台进程内对话智能体
 * （AgentScope HarnessAgent，经 {@link ChatAgentAppService}），不再走编码引擎——
 * 创建即访谈（{@code ProjectLifecycleAppService} 建项目后开场）与对话自由补充
 * （{@code ProjectAgentTaskAppService} 的 BA 路由）都续同一 BA 会话。
 *
 * <p><b>会话寻址（projectId → BA 会话的稳定绑定）</b>：sessionId = {@code ba-{projectId}}
 * 无表派生、userId = 项目 owner（State 槽位 (userId, sessionId) 跨轮一致，谁触发
 * 都不劈叉上下文）；答卡 settle 续跑（#48 等待点双向桥，userId 取等待点 body 的
 * 恢复私货——同槽位）与催促收敛（自由补充文本进上下文，BA 自主停止提问）都落回
 * 这条会话。自由补充遇在悬问答先化解（输入即答复 settle，见 {@link #runInterviewTurn}）。
 * 计量（role=BA 维度，engine=agentscope）与 role-assigned 帧序
 * （engine=agentscope，后续 task-start 由对话流自发）与引擎任务面同构。</p>
 *
 * <p><b>阶段计数</b>：提交即计入当前阶段任务数——计数门禁（taskCount≥1）与
 * 「PRD 已产出」谓词并行（#49 起 G1 = 计数 ∧ 谓词，计数照记不拆）；无 OPEN 期
 * （期后场景）不计数，访谈照常可谈
 * （工具与过程正交）。不做知识检索注入：访谈是对话上下文不是 run prompt
 * （引擎路径 A5 §3 注入口径不动）。</p>
 */
@Service
@Slf4j
public class BaInterviewAppService {

    /** BA 会话标识派生前缀（projectId → ba-{projectId}，稳定绑定勿动）。 */
    public static final String SESSION_PREFIX = "ba-";

    private final ProjectRepository projectRepository;
    private final IterationRepository iterationRepository;
    private final ChatAgentAppService chatAgentAppService;
    private final AgentStreamAppService streamAppService;
    private final AgentWaitQueryAppService waitQueryService;
    /** 惰性注入断 bean 环（projectWait → task → projectAgentTask → 本类）：化解路由是冷路径。 */
    private final ObjectProvider<ProjectWaitAppService> projectWaitAppServiceProvider;
    private final TransactionTemplate transactionTemplate;

    public BaInterviewAppService(ProjectRepository projectRepository,
            IterationRepository iterationRepository,
            ChatAgentAppService chatAgentAppService, AgentStreamAppService streamAppService,
            AgentWaitQueryAppService waitQueryService,
            ObjectProvider<ProjectWaitAppService> projectWaitAppServiceProvider,
            TransactionTemplate transactionTemplate) {
        this.projectRepository = projectRepository;
        this.iterationRepository = iterationRepository;
        this.chatAgentAppService = chatAgentAppService;
        this.streamAppService = streamAppService;
        this.waitQueryService = waitQueryService;
        this.projectWaitAppServiceProvider = projectWaitAppServiceProvider;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 跑一轮 BA 访谈（开场或自由补充共用——prompt 即用户侧输入，催促话术经此进
     * 上下文触发提前收敛）：异步提交即返回（runId 先生成随响应回，过程帧经 SSE；
     * 失败经 error 帧表达不炸调用方）。
     *
     * <p><b>在悬提问化解（两段式）</b>：会话还有在悬问答（BA 的 ask_user 未答）时，
     * 用户不打卡直接输入（自由补充/催促）＝对在悬问题的口头回答——以输入文本
     * settle 该等待点（续跑即消化），不开新轮（AgentScope 会话内有 ASKING 工具时
     * 新消息直接报错；且语义上答复优先）。此时响应锚回原 run（续跑帧同 runId），
     * 不重复计数（run 在开场已计）。settle 走 {@link ProjectWaitAppService}（SSE
     * wait-settled + QA 摄取与答卡 REST 同口径）。两段：提交时查一次（常见路径，
     * 锚回正确 runId）；执行前在闸内再复核一次（前序续跑可能刚挂起新提问——提交
     * 时快照已老，复核兜住竞态窗口，本轮静默折入原 run；响应已回新 runId 但其无帧
     * ——projectId 锚订阅不受影响，且该竞态窗多计一次阶段任务只会放宽计数门
     * （G1 主门禁已是「PRD 已产出」谓词，#49）。</p>
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public ProjectAgentTaskResponse runInterviewTurn(Long projectId, String prompt) {
        Project project = requireProject(projectId);
        Iteration openIteration = iterationRepository
                .findByProjectIdAndStatus(projectId, IterationStatus.OPEN)
                .orElse(null);
        String stage = openIteration != null ? openIteration.getStage()
                : ProjectMainChain.STAGE_CLOSED;
        RolePreset role = RolePreset.BA;
        String sessionId = SESSION_PREFIX + projectId;

        WaitPointResponse pendingQuestion = latestPendingQuestion(sessionId);
        if (pendingQuestion != null) {
            projectWaitAppServiceProvider.getObject().settle(projectId, pendingQuestion.waitId(),
                    answerSettlement(prompt));
            return new ProjectAgentTaskResponse(pendingQuestion.runId(), sessionId,
                    ChatAgentAppService.ENGINE, role, role.getName(), stage, true);
        }

        String runId = AgentRunContext.newRunId();
        emitRoleAssigned(projectId, runId, role, stage);
        ChatAgentCommand command = new ChatAgentCommand(
                runId,
                prompt,
                role.systemPrompt(),
                role.chatModelString(),
                sessionId,
                project.getOwnerAccountId() != null
                        ? project.getOwnerAccountId().toString() : null,
                new UsageContext(Long.toString(projectId),
                        ProjectAgentTaskAppService.usageDims(role.name(), stage, openIteration)),
                Long.toString(project.getWorkspaceId()),
                Map.of(AgentStreamAppService.PROJECT_FIELD, projectId.toString()));
        boolean accepted = chatAgentAppService.converseAsync(command, queued -> {
            // 执行时复核：前序续跑刚挂起新提问（提交时不可见）→ 本轮文本折为其答复
            WaitPointResponse raced = latestPendingQuestion(sessionId);
            if (raced == null) {
                return true;
            }
            projectWaitAppServiceProvider.getObject().settle(projectId, raced.waitId(),
                    answerSettlement(prompt));
            return false;
        });

        if (accepted && openIteration != null) {
            transactionTemplate.executeWithoutResult(status -> {
                openIteration.recordStageTask();
                iterationRepository.save(openIteration);
            });
        }
        return new ProjectAgentTaskResponse(runId, sessionId,
                ChatAgentAppService.ENGINE, role, role.getName(), stage, accepted);
    }

    // ---------- 内部 ----------

    /** 会话最新的在悬问答（化解路由输入；权限类等待不在此化解——卡片批复是唯一通道）。 */
    private WaitPointResponse latestPendingQuestion(String sessionId) {
        return waitQueryService.pendingOfSession(sessionId).stream()
                .filter(wait -> wait.kind() == WaitKind.QUESTION)
                .findFirst()
                .orElse(null);
    }

    /** 答复型 settle 命令（自由补充文本即答复）。 */
    private static ProjectWaitSettleCommand answerSettlement(String prompt) {
        return new ProjectWaitSettleCommand(WaitSettleCommand.TYPE_ANSWER,
                List.of(List.of(prompt)), null, null, null);
    }

    // ---------- 内部 ----------

    /** role-assigned 发射（run 提交前——帧序 role-assigned → task-start → …；engine=agentscope 标双轨）。 */
    private void emitRoleAssigned(Long projectId, String runId, RolePreset role, String stage) {
        streamAppService.publish(AgentEventTypes.ROLE_ASSIGNED, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, runId,
                AgentEventTypes.ROLE_FIELD, role.name(),
                AgentEventTypes.ROLE_LABEL_FIELD, role.getName(),
                AgentEventTypes.ROLE_STAGE_FIELD, stage,
                AgentEventTypes.ROLE_ENGINE_FIELD, ChatAgentAppService.ENGINE));
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
