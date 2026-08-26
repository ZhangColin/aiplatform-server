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
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
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
 * 编排管业务谓词（G1 = PRD 已产出，查项目状态位不查文件系统，#49；G3 = 无未关闭
 * Bug，经 {@link OpenBugQueryPort}——实现随 #26 提供，本片缝上默认无 Bug）；
 * 不满足 409 {@code PRJ_}。无门段（开发）无确认动作——
 * 推进归编排触发（首个测试任务，{@link ProjectAgentTaskAppService}）。G1 通过自动跑
 * Demo（A3 §2.3 前缀段自动）；G4 通过即收口（期 CLOSED，无交付段）。</p>
 *
 * <p>事务形态：留痕与期迁移一事务；SSE 在事务提交后发射（编排层发射制，
 * ADR-0001）；自动 Demo 起跑失败不回滚门决策（阶段已推进，失败经日志表达）。
 * G1 驳回触发 BA 续轮回流（#50）：驳回留痕落定后门操作内自动起 BA 续轮（意见
 * 注入 prompt 续 BA 会话——澄清追问或直接修订 PRD 再 savePrd，门重新就绪，
 * 往复至通过）；起跑失败不阻断驳回留痕（照「BA 起跑失败不回滚建项目」口径）。
 * G2 驳回触发 DEMO 修正回流（#46，同构镜像）：意见注入 prompt 续 Demo 会话起
 * 修正 run；带「涉及需求变更」标记时意见同时回流 BA 修订 PRD（#50 机制复用）。</p>
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
    private final BaInterviewAppService baInterviewAppService;
    private final ProjectQueryAppService queryAppService;
    private final PlatformNotificationAppService notificationAppService;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final TransactionTemplate transactionTemplate;

    public ProjectGateAppService(ProjectRepository projectRepository,
                                 IterationRepository iterationRepository,
                                 ConfirmationRepository confirmationRepository,
                                 StageAdvanceService stageAdvanceService,
                                 OpenBugQueryPort openBugQueryPort,
                                 ProjectAgentTaskAppService agentTaskAppService,
                                 BaInterviewAppService baInterviewAppService,
                                 ProjectQueryAppService queryAppService,
                                 PlatformNotificationAppService notificationAppService,
                                 ProjectKnowledgeAppService knowledgeAppService,
                                 TransactionTemplate transactionTemplate) {
        this.projectRepository = projectRepository;
        this.iterationRepository = iterationRepository;
        this.confirmationRepository = confirmationRepository;
        this.stageAdvanceService = stageAdvanceService;
        this.openBugQueryPort = openBugQueryPort;
        this.agentTaskAppService = agentTaskAppService;
        this.baInterviewAppService = baInterviewAppService;
        this.queryAppService = queryAppService;
        this.notificationAppService = notificationAppService;
        this.knowledgeAppService = knowledgeAppService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 门通过（无体——拍板即全部事实）：计数 ∧ 业务谓词 → 留痕 + 期推进/收口 →
     * SSE stage-changed(approved)；G1（需求确认）通过自动跑 Demo。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_009 当前阶段无确认门；
     *                              PRJ_016 G1 谓词不满足（PRD 未产出）；
     *                              PRJ_008 G3 谓词不满足（存在未关闭 Bug）；
     *                              PRJ_007 计数门禁不足；PRJ_010 无 OPEN 期
     */
    public ProjectDetailResponse approve(Long projectId) {
        Project project = requireProject(projectId);
        Iteration iteration = openIterationOf(projectId);
        StageEntry current = stageOf(iteration.getStage());
        requireGateOf(current);

        // 引擎计数门禁先行（内存裁决）；业务谓词后查（A3 §2.4 编排半边，与 gateView
        // 就绪同口径）：需求确认（G1）= PRD 已产出（查项目状态位不查文件系统，#49——
        // 计数门禁并行保留）；开发完成确认（G3，actor=开发平台）= 无未关闭 Bug。
        AdvanceResult result = stageAdvanceService.advance(ProjectMainChain.definition(),
                iteration.getStage(), iteration.getStageTaskCount());
        if (result instanceof AdvanceResult.GateBlocked) {
            throw new ApplicationException(ProjectMessage.GATE_TASKS_INSUFFICIENT);
        }
        if (ProjectMainChain.STAGE_BA.equals(current.name())
                && project.getPrdProducedAt() == null) {
            throw new ApplicationException(ProjectMessage.GATE_PRD_NOT_PRODUCED);
        }
        if (ProjectMainChain.GATE_ACTOR_PLATFORM.equals(current.exitGate().actor())
                && openBugQueryPort.hasOpenBugs(projectId)) {
            throw new ApplicationException(ProjectMessage.GATE_OPEN_BUGS);
        }
        StageEntry next = ((AdvanceResult.Advanced) result).to();
        ConfirmationKind kind = confirmationKindOf(current);

        Confirmation confirmation = transactionTemplate.execute(tx -> {
            Confirmation saved = confirmationRepository.save(Confirmation.approveOf(
                    iteration.getId(), kind, RequestContext.getUserId()));
            if (next.terminal()) {
                iteration.close(next.name());
            } else {
                iteration.advanceTo(next.name());
            }
            iterationRepository.save(iteration);
            return saved;
        });

        notificationAppService.publish(ProjectEventTypes.STAGE_CHANGED,
                StageChangedPayload.approved(projectId, next.name()));

        // A5 §1 摄取（事务提交后，失败降级不炸）：门决策留痕 FEEDBACK + 被通过
        // 阶段的产物清单 ARTIFACT（v1 仅需求梳理段 docs/PRD.md）。置于自动 Demo 之前——
        // Demo run 的检索注入可命中刚入库的 PRD。
        knowledgeAppService.indexFeedback(projectId, confirmation);
        knowledgeAppService.indexStageArtifacts(projectId, current.name());

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
        return queryAppService.detail(projectId);
    }

    /**
     * 门驳回（reason 必填）：一律停留当前阶段（A3 §3——无「退回哪段」的问题），
     * 留痕落 {@code prj_confirmations}，SSE stage-changed(rejected + reason)。
     * G1（BA 段）驳回落留痕后自动起 BA 续轮（#50 驳回回流；起跑失败不阻断留痕）。
     * G2（Demo 段）驳回落留痕后自动起 DEMO 修正 run（#46——意见注入 prompt 续
     * Demo 会话，修正完门重新就绪）；{@code requirementChange} 置位时意见同时回流
     * BA（复用 #50 机制触发 PRD 修订——PRD 更新后修正以新 PRD 为准；v1 只认显式
     * 标记不做语义自动判定）。两路起跑失败互不牵连、不阻断驳回留痕。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_011 reason 空白；
     *                              PRJ_009 当前阶段无确认门；PRJ_010 无 OPEN 期
     */
    public ProjectDetailResponse reject(Long projectId, String reason, boolean requirementChange) {
        requireProject(projectId);
        Iteration iteration = openIterationOf(projectId);
        StageEntry current = stageOf(iteration.getStage());
        requireGateOf(current);
        // 驳回停留经引擎裁决（校验阶段仍在主链；迁移永不发生）
        stageAdvanceService.reject(ProjectMainChain.definition(), iteration.getStage());
        ConfirmationKind kind = confirmationKindOf(current);

        // reason 必填由留痕不变量兜底（DomainException PRJ_011；REST 面另有 @NotBlank）
        Confirmation confirmation = transactionTemplate.execute(tx ->
                confirmationRepository.save(Confirmation.rejectOf(iteration.getId(), kind,
                        RequestContext.getUserId(), reason)));

        String strippedReason = reason.strip();
        notificationAppService.publish(ProjectEventTypes.STAGE_CHANGED,
                StageChangedPayload.rejected(projectId, iteration.getStage(), strippedReason));

        // A5 §1 摄取（事务提交后，失败降级不炸）：驳回 reason 是验收反馈纪要来源
        knowledgeAppService.indexFeedback(projectId, confirmation);

        // #50 驳回回流：G1（需求确认）驳回 → 自动起 BA 续轮（意见注入 prompt 续
        // BA 会话：意见不清先澄清回问答循环，或修订 PRD 再 savePrd → 门重新就绪，
        // 往复至通过）。起跑失败不阻断驳回留痕（失败经日志表达——意见仍可经自由
        // 补充通道手动进 BA 会话）。
        if (ProjectMainChain.STAGE_BA.equals(current.name())) {
            reflowQuietly(projectId, "BA 续轮",
                    () -> baInterviewAppService.runInterviewTurn(projectId,
                            RolePreset.rejectReflowPrompt(strippedReason)));
        }

        // #46 驳回回流：G2（Demo 确认）驳回 → 自动起 DEMO 修正 run（意见注入 prompt
        // 续 Demo 会话：修正完门重新就绪，往复至通过）；带「涉及需求变更」标记时意见
        // 同时回流 BA（触发 PRD 修订，document-updated 可观测——修正 run 提示重读
        // 最新 PRD 对齐，更新落地前的偏差由循环下一轮收敛）。两路独立护栏：任一起跑
        // 失败只经日志表达，不阻断驳回留痕也不吞另一路。
        if (ProjectMainChain.STAGE_DEMO.equals(current.name())) {
            reflowQuietly(projectId, "Demo 修正",
                    () -> agentTaskAppService.dispatchDemoCorrectionRun(projectId,
                            RolePreset.demoCorrectionPrompt(strippedReason, requirementChange)));
            if (requirementChange) {
                reflowQuietly(projectId, "BA 回流",
                        () -> baInterviewAppService.runInterviewTurn(projectId,
                                RolePreset.demoRejectRequirementChangePrompt(strippedReason)));
            }
        }
        return queryAppService.detail(projectId);
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

    /**
     * 驳回回流起跑护栏（#50/#46 共用）：起跑失败不阻断驳回留痕、多路回流互不牵连
     * （失败经日志表达——意见仍可经自由补充通道/手动下任务进会话）。
     */
    private void reflowQuietly(Long projectId, String reflow, Runnable start) {
        try {
            start.run();
        } catch (RuntimeException e) {
            log.warn("项目 {} 驳回后 {}起跑失败（驳回留痕不受影响）", projectId, reflow, e);
        }
    }
}
