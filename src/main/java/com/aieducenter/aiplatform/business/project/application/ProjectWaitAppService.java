package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.BaseCodeMessage;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentTaskAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.SettleResult;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.business.identity.application.AccountAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectWaitSettleCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectWaitResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.port.DeferredTaskPort;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 项目等待点桥接（片2b 端口的项目视角，A1 §1 口子①）：列等待点/答复（问答/
 * 权限）按项目寻址转底座等待点通道；答复成功后发射 {@code wait-settled}（编排层
 * 发射制：副作用真实落定后）。校验链（404/409/deny cap）全在底座，本层只做
 * 寻址映射与 SSE 桥接——「对话建项目」的问答与开发平台共用同一套 wait 语义
 * （A3 §5，零新增机制）。Deferred 转任务（A1 §3.1，#27）：关等待点 + 经
 * {@link DeferredTaskPort} 建任务（任务存 waitId 引用，TaskCompleted 据此续跑）。
 */
@Service
@Slf4j
public class ProjectWaitAppService {

    private final ProjectRepository projectRepository;
    private final AgentWaitAppService agentWaitAppService;
    private final AgentTaskAppService agentTaskAppService;
    private final AgentStreamAppService streamAppService;
    private final DeferredTaskPort deferredTaskPort;
    private final ProjectQueryAppService projectQueryAppService;
    private final AccountAppService accountAppService;
    private final ProjectKnowledgeAppService knowledgeAppService;

    public ProjectWaitAppService(ProjectRepository projectRepository,
                                 AgentWaitAppService agentWaitAppService,
                                 AgentTaskAppService agentTaskAppService,
                                 AgentStreamAppService streamAppService,
                                 DeferredTaskPort deferredTaskPort,
                                 ProjectQueryAppService projectQueryAppService,
                                 AccountAppService accountAppService,
                                 ProjectKnowledgeAppService knowledgeAppService) {
        this.projectRepository = projectRepository;
        this.agentWaitAppService = agentWaitAppService;
        this.agentTaskAppService = agentTaskAppService;
        this.streamAppService = streamAppService;
        this.deferredTaskPort = deferredTaskPort;
        this.projectQueryAppService = projectQueryAppService;
        this.accountAppService = accountAppService;
        this.knowledgeAppService = knowledgeAppService;
    }

    /**
     * 项目的待处理等待点（跨会话聚合，新→旧；「待我处理」列表的数据源之一）。
     */
    public List<ProjectWaitResponse> pendingWaits(Long projectId) {
        Project project = requireProject(projectId);
        return agentWaitAppService
                .pendingWaits(Long.toString(project.getWorkspaceId())).stream()
                .map(ProjectWaitAppService::toResponse)
                .toList();
    }

    /**
     * 答复等待点（问答答复 / 权限批准或拒绝 / 转任务）：转底座 settle（校验链
     * 404/409、引擎送达；deny cap 判定在结果回报），成功后发 SSE wait-settled。
     * type=deferred（转任务，A1 §3.1，#27）：先过守卫（task 载荷必填 400 /
     * owner 403 / 指派账号 404）再关等待点，随后经端口建任务（waitId 引用）——
     * 任务确认后的回填续跑由 {@code TaskBackfillListener} 接力。
     *
     * <p>deny cap 接续（票 #38）：底座回报达 cap 时经 {@link AgentTaskAppService}
     * 的 terminateRun（与 cancelRun 共用路径）执行 abort + 收口 + 平台终态帧——
     * 在本层 settle 帧之后调用，保住帧序 wait-settled × N → task-finish(cancelled)
     * 最后落地（前端 wait-settled 一律把 run 拉回 running）。</p>
     */
    public void settle(Long projectId, String waitId, ProjectWaitSettleCommand command) {
        Project project = requireProject(projectId);
        String workspaceId = Long.toString(project.getWorkspaceId());
        DeferredSettlement deferred = WaitSettleCommand.TYPE_DEFERRED.equals(command.type())
                ? requireDeferredSettlement(projectId, waitId, command)
                : null;
        SettleResult result = agentWaitAppService.settle(workspaceId,
                waitId, new WaitSettleCommand(command.type(), command.answers(),
                        command.approve(), command.note()));
        if (deferred != null) {
            String taskId = deferredTaskPort.createFromWait(projectId, waitId,
                    deferred.payload().title(), deferred.content(), deferred.payload().assigneeAccountId());
            log.info("[project] 等待点 {} 转任务完成：taskId={}", waitId, taskId);
        }

        // SSE（副作用落定后：settle 成功才发）；关闭结果缺失的异常形态只记日志跳过
        WaitPointResponse settled = result.settled();
        if (settled.settleOutcome() == null) {
            log.warn("等待点 {} 答复后无关闭结果，跳过 SSE 发射", waitId);
            return;
        }
        emitWaitSettled(projectId, toResponse(settled));

        // A5 §1 QA 摄取（settle(Answer) 编排处即刻，失败降级不炸）：问答对的
        // 问题（body）与答复（answers）成纪要素材；权限答复/转任务不摄取
        if (settled.kind() == WaitKind.QUESTION
                && settled.settleOutcome() == WaitOutcome.ANSWERED) {
            knowledgeAppService.indexQa(projectId, waitId, settled.body(),
                    settled.summary(), command.answers());
        }

        if (result.denyCapped()) {
            agentTaskAppService.terminateRun(workspaceId, result.engine(),
                    settled.sessionId(), settled.runId(),
                    Map.of(AgentStreamAppService.PROJECT_FIELD, Long.toString(projectId)));
        }
    }

    // ---------- 内部 ----------

    /** 转任务前置守卫（settle 前先行——任务建不成的输入不关等待点）：task 载荷
     * 必填（400）、owner（通用 403——同语义的任务侧守卫 TASK_009 在端口实现内
     * 再兜一层）、指派账号存在（通用 404）。 */
    private DeferredSettlement requireDeferredSettlement(Long projectId, String waitId,
                                                         ProjectWaitSettleCommand command) {
        ProjectWaitSettleCommand.DeferredTaskPayload payload = command.task();
        if (payload == null) {
            throw new ApplicationException(BaseCodeMessage.BAD_REQUEST,
                    "type=deferred 必填 task（转任务建任务入参）");
        }
        if (!RequestContext.getUserId().equals(projectQueryAppService.ownerAccountIdOf(projectId))) {
            throw new ApplicationException(BaseCodeMessage.FORBIDDEN);
        }
        if (!accountAppService.exists(payload.assigneeAccountId())) {
            throw new ApplicationException(BaseCodeMessage.NOT_FOUND, "指派账号不存在");
        }
        return new DeferredSettlement(payload, contentOf(payload, waitId, command.note()));
    }

    /** 任务内容缺省：等待点摘要 + 备注（转任务的来龙去脉留给指派人看）。 */
    private String contentOf(ProjectWaitSettleCommand.DeferredTaskPayload payload, String waitId,
                             String note) {
        if (payload.content() != null && !payload.content().isBlank()) {
            return payload.content();
        }
        StringBuilder content = new StringBuilder("转任务来源：项目智能体等待点 ").append(waitId);
        agentWaitAppService.wait(waitId).map(WaitPointResponse::summary)
                .ifPresent(summary -> content.append("（").append(summary).append("）"));
        if (note != null && !note.isBlank()) {
            content.append("\n备注：").append(note.strip());
        }
        return content.toString();
    }

    /** 转任务结算中间态（守卫通过即冻结建任务入参）。 */
    private record DeferredSettlement(
            ProjectWaitSettleCommand.DeferredTaskPayload payload, String content) {
    }

    private void emitWaitSettled(Long projectId, ProjectWaitResponse settled) {
        if (settled.runId() == null || settled.runId().isBlank()) {
            log.warn("等待点 {} 无关联运行，跳过 wait-settled 发射（agent 流 payload 必带 runId）",
                    settled.waitId());
            return;
        }
        streamAppService.publish(AgentEventTypes.WAIT_SETTLED, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, settled.runId(),
                AgentEventTypes.WAIT_ID_FIELD, settled.waitId(),
                AgentEventTypes.WAIT_OUTCOME_FIELD,
                outcomeOf(settled.settleOutcome())));
    }

    /** 底座 WaitPointResponse → 项目视角投影（projectId 来自路径不重复携带；
     *  *Name 随附由 record 紧凑构造器从枚举派生）。 */
    private static ProjectWaitResponse toResponse(WaitPointResponse wait) {
        return new ProjectWaitResponse(wait.waitId(), wait.kind(), null,
                wait.status(), null, wait.summary(), wait.sessionId(),
                wait.runId(), wait.engineRef(), wait.body(), wait.settleOutcome(),
                null, wait.raisedAt(), wait.settledAt());
    }

    private static String outcomeOf(WaitOutcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT); // answered/approved/denied/deferred
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
