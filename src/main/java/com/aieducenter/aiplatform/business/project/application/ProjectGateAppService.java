package com.aieducenter.aiplatform.business.project.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.process.domain.model.AdvanceResult;
import com.aieducenter.aiplatform.base.process.domain.model.ExitGate;
import com.aieducenter.aiplatform.base.process.domain.model.StageEntry;
import com.aieducenter.aiplatform.base.process.domain.service.StageAdvanceService;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectAgentTaskCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Confirmation;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ConfirmationKind;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.port.OpenBugQueryPort;
import com.aieducenter.aiplatform.business.project.domain.repository.ConfirmationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 门操作用例（A3 §2/§3，demo ProjectController.approve/reject 的重写）：四扇门
 * 的人拍板——approve 推进（末门即收口）/ reject 一律停留当前阶段，决策 append-only
 * 落 {@code prj_confirmations}（approve 也留痕，account_id 第一天记 approver）。
 *
 * <p>门禁分层（A3 §2.4）：引擎管计数（{@link StageAdvanceService}，minTasks 按门），
 * 编排管业务谓词（G3 = 无未关闭 Bug，经 {@link OpenBugQueryPort}——实现随 #26 提供，
 * 本片缝上默认无 Bug）；不满足 409 {@code PRJ_}。无门段（开发）无确认动作——
 * 推进归编排触发（首个测试任务，{@link ProjectAgentTaskAppService}）。G1 通过自动跑
 * Demo（A3 §2.3 前缀段自动）；G4 通过即收口（期 CLOSED，无交付段）。</p>
 *
 * <p>事务形态：留痕与期迁移一事务；SSE 在事务提交后发射（编排层发射制，
 * ADR-0001）；自动 Demo 起跑失败不回滚门决策（阶段已推进，失败经日志表达）。</p>
 */
@Service
@Slf4j
public class ProjectGateAppService {

    private final ProjectRepository projectRepository;
    private final IterationRepository iterationRepository;
    private final ConfirmationRepository confirmationRepository;
    private final StageAdvanceService stageAdvanceService;
    private final OpenBugQueryPort openBugQueryPort;
    private final ProjectAgentTaskAppService agentTaskAppService;
    private final ProjectLifecycleAppService lifecycleAppService;
    private final PlatformNotificationAppService notificationAppService;
    private final TransactionTemplate transactionTemplate;

    public ProjectGateAppService(ProjectRepository projectRepository,
                                 IterationRepository iterationRepository,
                                 ConfirmationRepository confirmationRepository,
                                 StageAdvanceService stageAdvanceService,
                                 OpenBugQueryPort openBugQueryPort,
                                 ProjectAgentTaskAppService agentTaskAppService,
                                 ProjectLifecycleAppService lifecycleAppService,
                                 PlatformNotificationAppService notificationAppService,
                                 TransactionTemplate transactionTemplate) {
        this.projectRepository = projectRepository;
        this.iterationRepository = iterationRepository;
        this.confirmationRepository = confirmationRepository;
        this.stageAdvanceService = stageAdvanceService;
        this.openBugQueryPort = openBugQueryPort;
        this.agentTaskAppService = agentTaskAppService;
        this.lifecycleAppService = lifecycleAppService;
        this.notificationAppService = notificationAppService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 门通过（无体——拍板即全部事实）：计数 ∧ 业务谓词 → 留痕 + 期推进/收口 →
     * SSE stage-changed(approved)；G1（需求确认）通过自动跑 Demo。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_009 当前阶段无确认门；
     *                              PRJ_008 G3 谓词不满足（存在未关闭 Bug）；
     *                              PRJ_007 计数门禁不足；PRJ_010 无 OPEN 期
     */
    public ProjectResponse approve(Long projectId) {
        requireProject(projectId);
        Iteration iteration = openIterationOf(projectId);
        StageEntry current = stageOf(iteration.getStage());
        requireGateOf(current);

        // 引擎计数门禁先行（内存裁决）；业务谓词后查（A3 §2.4 编排半边）：开发完成
        // 确认（actor=开发平台）= 无未关闭 Bug——查询端口实现随 #26 提供，本片默认
        // 无 Bug，缝在、行为等同放行。
        AdvanceResult result = stageAdvanceService.advance(ProjectMainChain.definition(),
                iteration.getStage(), iteration.getStageTaskCount());
        if (result instanceof AdvanceResult.GateBlocked) {
            throw new ApplicationException(ProjectMessage.GATE_TASKS_INSUFFICIENT);
        }
        if (ProjectMainChain.GATE_ACTOR_PLATFORM.equals(current.exitGate().actor())
                && openBugQueryPort.hasOpenBugs(projectId)) {
            throw new ApplicationException(ProjectMessage.GATE_OPEN_BUGS);
        }
        StageEntry next = ((AdvanceResult.Advanced) result).to();
        ConfirmationKind kind = confirmationKindOf(current);

        transactionTemplate.executeWithoutResult(tx -> {
            confirmationRepository.save(Confirmation.approveOf(iteration.getId(), kind,
                    RequestContext.getUserId()));
            if (next.terminal()) {
                iteration.close(next.name());
            } else {
                iteration.advanceTo(next.name());
            }
            iterationRepository.save(iteration);
        });

        notificationAppService.publish(ProjectEventTypes.STAGE_CHANGED,
                StageChangedPayload.approved(projectId, next.name()));

        // 前缀段自动（A3 §2.3）：G1 需求确认通过 → 自动跑 Demo（完事 preview：
        // GET preview 暴露端口 + preview-ready）。起跑失败不回滚门决策。
        if (ProjectMainChain.STAGE_BA.equals(current.name())) {
            try {
                agentTaskAppService.dispatchTask(projectId,
                        new ProjectAgentTaskCommand(RolePreset.DEMO_KICKOFF_PROMPT,
                                RolePreset.DEMO));
            } catch (RuntimeException e) {
                log.warn("项目 {} 自动 Demo 起跑失败（门通过不回滚）", projectId, e);
            }
        }
        return lifecycleAppService.get(projectId);
    }

    /**
     * 门驳回（reason 必填）：一律停留当前阶段（A3 §3——无「退回哪段」的问题），
     * 留痕落 {@code prj_confirmations}，SSE stage-changed(rejected + reason)。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_011 reason 空白；
     *                              PRJ_009 当前阶段无确认门；PRJ_010 无 OPEN 期
     */
    public ProjectResponse reject(Long projectId, String reason) {
        requireProject(projectId);
        Iteration iteration = openIterationOf(projectId);
        StageEntry current = stageOf(iteration.getStage());
        requireGateOf(current);
        // 驳回停留经引擎裁决（校验阶段仍在主链；迁移永不发生）
        stageAdvanceService.reject(ProjectMainChain.definition(), iteration.getStage());
        ConfirmationKind kind = confirmationKindOf(current);

        // reason 必填由留痕不变量兜底（DomainException PRJ_011；REST 面另有 @NotBlank）
        transactionTemplate.executeWithoutResult(tx ->
                confirmationRepository.save(Confirmation.rejectOf(iteration.getId(), kind,
                        RequestContext.getUserId(), reason)));

        notificationAppService.publish(ProjectEventTypes.STAGE_CHANGED,
                StageChangedPayload.rejected(projectId, iteration.getStage(), reason.strip()));
        return lifecycleAppService.get(projectId);
    }

    // ---------- 内部 ----------

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }

    /** v1 寻址：项目的 OPEN 期（A3 §2.1）；无 OPEN 期 = 主链已收口或未初始化。 */
    private Iteration openIterationOf(Long projectId) {
        return iterationRepository
                .findByProjectIdAndStatus(projectId, IterationStatus.OPEN)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.ITERATION_NOT_OPEN));
    }

    private StageEntry stageOf(String stage) {
        return ProjectMainChain.definition().find(stage)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE));
    }

    /** 门操作只对有门阶段成立：无门段（开发）的推进归编排触发，不是人拍板。 */
    private ExitGate requireGateOf(StageEntry stage) {
        ExitGate gate = stage.exitGate();
        if (gate == null) {
            throw new ApplicationException(ProjectMessage.STAGE_NO_GATE);
        }
        return gate;
    }

    /** 阶段 → 确认种类（主链定义唯一编码；有门段必有种类——缺失即定义装配错误）。 */
    private ConfirmationKind confirmationKindOf(StageEntry stage) {
        return ProjectMainChain.confirmationKindOf(stage.name())
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE));
    }
}
