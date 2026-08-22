package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectWaitSettleCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectWaitResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 项目等待点桥接（片2b 端口的项目视角，A1 §1 口子①）：列等待点/答复（问答/
 * 权限）按项目寻址转底座等待点通道；答复成功后发射 {@code wait-settled}（编排层
 * 发射制：副作用真实落定后）。校验链（404/409/deny cap）全在底座，本层只做
 * 寻址映射与 SSE 桥接——「对话建项目」的问答与开发平台共用同一套 wait 语义
 * （A3 §5，零新增机制）。Deferred 的转任务动作归 A4 修复编排链（票 #27）。
 */
@Service
@Slf4j
public class ProjectWaitAppService {

    private final ProjectRepository projectRepository;
    private final AgentWaitAppService agentWaitAppService;
    private final AgentStreamAppService streamAppService;

    public ProjectWaitAppService(ProjectRepository projectRepository,
                                 AgentWaitAppService agentWaitAppService,
                                 AgentStreamAppService streamAppService) {
        this.projectRepository = projectRepository;
        this.agentWaitAppService = agentWaitAppService;
        this.streamAppService = streamAppService;
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
     * 答复等待点（问答答复 / 权限批准或拒绝 / 转任务关闭）：转底座 settle
     * （校验链 404/409、引擎送达、deny cap 平台终止），成功后发 SSE wait-settled。
     */
    public void settle(Long projectId, String waitId, ProjectWaitSettleCommand command) {
        Project project = requireProject(projectId);
        String workspaceId = Long.toString(project.getWorkspaceId());
        agentWaitAppService.settle(workspaceId, waitId, new WaitSettleCommand(
                command.type(), command.answers(), command.approve(), command.note()));

        // SSE（副作用落定后：settle 成功才发）；runId 缺失的异常等待点只记日志跳过
        agentWaitAppService.wait(waitId)
                .map(ProjectWaitAppService::toResponse)
                .filter(settled -> settled.settleOutcome() != null)
                .ifPresentOrElse(settled -> emitWaitSettled(projectId, settled),
                        () -> log.warn("等待点 {} 答复后读不到关闭结果，跳过 SSE 发射", waitId));
    }

    // ---------- 内部 ----------

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
