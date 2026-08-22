package com.aieducenter.aiplatform.business.project.application;

import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;
import com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext;
import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentTaskAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentTaskDispatchCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentTaskResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectAgentTaskCommand;
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
 * 项目智能体任务编排（demo AgentController.task 的重写，B0 §1 拆解）：角色卡
 * 解析（显式入参或阶段默认）→ role-assigned 发射 → 引擎下发（底座编排入口带上
 * runId/计量归属/流关联）→ 阶段计数。
 *
 * <p>计量归属（A1 §2.4 + A6 §3）：subject=projectId、dims={role, stage, iterationId?}——
 * iterationId 有 OPEN 期才带（run 发起时快照；期后修复 run 不带，归项目不归期），
 * 业务维度随 UsageEvent 落 met_usage_events；SSE 桥接（ADR-0001 编排层发射制）：projectId
 * 经 {@link AgentRunContext} 注入 agent 流每帧（含底座补发的 wait-raised），
 * {@code role-assigned} 由本层在 run 下发前发射（帧序 role-assigned →
 * task-start → session-created → …）。引擎交互不进业务事务（秒到分钟级），
 * 阶段计数是单行落库（仓储自带事务）。</p>
 *
 * <p>开发→测试推进（A3 §2.3 唯一触发）：首个测试任务（显式 TEST 角色且期在
 * 开发段）被引擎接受即 advance + stage-changed（无门段，非人拍板）。</p>
 *
 * <p>知识检索注入单点缝（A5 §3）：dispatchTask / dispatchFixRun 的 run 下发前
 * 经 {@link ProjectKnowledgeAppService#injectForRun}——全局跨项目纯相似命中前置
 * 注入 prompt 并发射 knowledge-retrieved（帧序 role-assigned →
 * knowledge-retrieved → task-start）；空命中/降级原样下发。</p>
 */
@Service
@Slf4j
public class ProjectAgentTaskAppService {

    /** 修复 run 的计量角色维度值（A4 §4：与 DEV 开发用量区分的用途标记）。 */
    public static final String FIX_ROLE_DIM = "FIX";

    /** 计量维度键（写侧三处组装共用；读侧 ProjectQueryAppService 对齐——单点定义防漂移）。 */
    public static final String DIM_ROLE = "role";

    /** 见 {@link #DIM_ROLE}。 */
    public static final String DIM_STAGE = "stage";

    /** 见 {@link #DIM_ROLE}（A6 §3：run 发起时快照；期后修复 run 不带）。 */
    public static final String DIM_ITERATION = "iterationId";

    private final ProjectRepository projectRepository;
    private final IterationRepository iterationRepository;
    private final AgentTaskAppService agentTaskAppService;
    private final AgentStreamAppService streamAppService;
    private final PlatformNotificationAppService notificationAppService;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final TransactionTemplate transactionTemplate;

    public ProjectAgentTaskAppService(ProjectRepository projectRepository,
                                      IterationRepository iterationRepository,
                                      AgentTaskAppService agentTaskAppService,
                                      AgentStreamAppService streamAppService,
                                      PlatformNotificationAppService notificationAppService,
                                      ProjectKnowledgeAppService knowledgeAppService,
                                      TransactionTemplate transactionTemplate) {
        this.projectRepository = projectRepository;
        this.iterationRepository = iterationRepository;
        this.agentTaskAppService = agentTaskAppService;
        this.streamAppService = streamAppService;
        this.notificationAppService = notificationAppService;
        this.knowledgeAppService = knowledgeAppService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 下发项目任务（手动 DEV/ARCH 或前缀段自动 BA/DEMO）：run 被引擎接受即计入
     * 当前阶段计数（门禁输入）。期已收口不计数（工具与过程正交，任务照常跑）。
     */
    public ProjectAgentTaskResponse dispatchTask(Long projectId, ProjectAgentTaskCommand command) {
        Project project = requireProject(projectId);
        Iteration openIteration = iterationRepository
                .findByProjectIdAndStatus(projectId, IterationStatus.OPEN)
                .orElse(null);
        RolePreset role = resolveRole(command.role(), openIteration);
        String stage = openIteration != null ? openIteration.getStage()
                : ProjectMainChain.STAGE_CLOSED;

        String runId = AgentRunContext.newRunId();
        emitRoleAssigned(projectId, runId, role, stage, project.getEngine());
        // 知识检索注入单点缝（A5 §3）：query = 任务 prompt 全文（截断），命中前置
        // 注入 + knowledge-retrieved 发射；空命中/降级 = 原 prompt 照发
        String prompt = knowledgeAppService.injectForRun(projectId, runId, command.prompt());

        AgentTaskResponse result = agentTaskAppService.dispatch(
                Long.toString(project.getWorkspaceId()),
                new AgentTaskDispatchCommand(prompt, role.systemPrompt(),
                        role.modelId(), project.getEngine(), null),
                new AgentRunContext(runId,
                        new UsageContext(Long.toString(projectId),
                                usageDims(role.name(), stage, openIteration)),
                        Map.of(AgentStreamAppService.PROJECT_FIELD, Long.toString(projectId))));

        if (result.accepted() && openIteration != null) {
            // A3 §2.3 开发→测试的唯一触发：创建首个测试任务（期在开发段 + TEST 角色）
            // → advance；已在测试段（复测）或期收口不动（A4 §5 守卫同口径，#26 的
            // 任务端点经本端口复用此编排）。接受才触发——被拒的 run 没有创建事实。
            boolean firstTestTask = ProjectMainChain.STAGE_DEV.equals(openIteration.getStage())
                    && role == RolePreset.TEST;
            transactionTemplate.executeWithoutResult(status -> {
                if (firstTestTask) {
                    openIteration.advanceTo(ProjectMainChain.STAGE_TEST);
                }
                openIteration.recordStageTask();
                iterationRepository.save(openIteration);
            });
            if (firstTestTask) {
                notificationAppService.publish(ProjectEventTypes.STAGE_CHANGED,
                        StageChangedPayload.plain(projectId, ProjectMainChain.STAGE_TEST));
            }
        }
        // stage 为下发时快照（首个测试任务的任务本身发起于开发段，计数已落测试段）
        return new ProjectAgentTaskResponse(result.runId(), result.sessionId(),
                result.engine(), role, role.getName(), stage, result.accepted());
    }

    /**
     * 修复 run 下发（A4 §4 落码归属，#27 修复编排链经此复用片5 编排）：DEV 类
     * preset（systemPrompt/modelId——修复就是写代码），计量 dims 以 role=FIX
     * 区分修复用量（SSE role-assigned 照 DEV preset 报——呈现的是干活的角色卡，
     * 计量记的是用途）；SSE 桥接与阶段计数全继承 dispatchTask。期 CLOSED（无
     * OPEN 期，期后修复）无期可挂，跳过阶段计数——工具正交。runId 由编排方
     * （task BC 链）生成传入（Bug 行先落 in-flight 标记再下发，重启可恢复）；
     * {@code eventObserver} 收底座流桥逐帧回调——链在其上收终态（task-finish/
     * error）。不处 advance：修复不是测试任务，开发→测试唯一触发不涉。
     */
    public ProjectAgentTaskResponse dispatchFixRun(Long projectId, String prompt, String runId,
                                                   Consumer<AgentEvent> eventObserver) {
        Project project = requireProject(projectId);
        Iteration openIteration = iterationRepository
                .findByProjectIdAndStatus(projectId, IterationStatus.OPEN)
                .orElse(null);
        RolePreset role = RolePreset.DEV;
        String stage = openIteration != null ? openIteration.getStage()
                : ProjectMainChain.STAGE_CLOSED;

        emitRoleAssigned(projectId, runId, role, stage, project.getEngine());
        // 检索注入单点同 dispatchTask（A5 §3：修复 run 天然命中历史 Bug——测试阶段
        // 叙事的兑现点）
        String effectivePrompt = knowledgeAppService.injectForRun(projectId, runId, prompt);

        AgentTaskResponse result = agentTaskAppService.dispatch(
                Long.toString(project.getWorkspaceId()),
                new AgentTaskDispatchCommand(effectivePrompt, role.systemPrompt(),
                        role.modelId(), project.getEngine(), null),
                new AgentRunContext(runId,
                        new UsageContext(Long.toString(projectId),
                                usageDims(FIX_ROLE_DIM, stage, openIteration)),
                        Map.of(AgentStreamAppService.PROJECT_FIELD, Long.toString(projectId))),
                eventObserver);

        if (result.accepted() && openIteration != null) {
            // 修复 run 计入当前阶段计数——测试段 minTasks=1 由修复 run 凑上（A4 §5）
            transactionTemplate.executeWithoutResult(status -> {
                openIteration.recordStageTask();
                iterationRepository.save(openIteration);
            });
        }
        return new ProjectAgentTaskResponse(result.runId(), result.sessionId(),
                result.engine(), role, role.getName(), stage, result.accepted());
    }

    /**
     * A4 §5 期联动：创建（人）测试任务时的 advance 守卫——**开发→测试的唯一
     * 触发**（与 dispatchTask 的智能体侧同口径，A3 §2.3）。期在开发段 →
     * advance + stage-changed（编排触发，非人拍板，无计数——阶段计数只记
     * agent 任务）；已在测试段（复测场景）/无 OPEN 期（期 CLOSED，期后修复）
     * → 不动。返回是否推进（日志/测试面用）。
     *
     * <p>项目存在性在此校验（PRJ_001）——task BC 建任务的前置。</p>
     */
    public boolean advanceToTestOnTestTaskCreation(Long projectId) {
        requireProject(projectId);
        Iteration openIteration = iterationRepository
                .findByProjectIdAndStatus(projectId, IterationStatus.OPEN)
                .orElse(null);
        if (openIteration == null
                || !ProjectMainChain.STAGE_DEV.equals(openIteration.getStage())) {
            return false; // 测试段（复测）/已收口（期后修复）不动，A4 §5
        }
        transactionTemplate.executeWithoutResult(status -> {
            openIteration.advanceTo(ProjectMainChain.STAGE_TEST);
            iterationRepository.save(openIteration);
        });
        notificationAppService.publish(ProjectEventTypes.STAGE_CHANGED,
                StageChangedPayload.plain(projectId, ProjectMainChain.STAGE_TEST));
        return true;
    }

    // ---------- 内部 ----------

    /** 角色解析：显式入参优先（REST 整型 code 已解码）；缺省取 OPEN 期当前阶段
     * 的默认角色（无则 409 PRJ_004）。defaultRole 字符串解析失败是主链定义与
     * preset 的装配错误，防御性 400 PRJ_003。 */
    private RolePreset resolveRole(RolePreset explicit, Iteration openIteration) {
        if (explicit != null) {
            return explicit;
        }
        String stage = openIteration != null ? openIteration.getStage()
                : ProjectMainChain.STAGE_CLOSED;
        String defaultRole = ProjectMainChain.definition().find(stage)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE))
                .defaultRole();
        if (defaultRole == null) {
            throw new ApplicationException(ProjectMessage.ROLE_REQUIRED);
        }
        return RolePreset.byName(defaultRole)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.ROLE_UNKNOWN));
    }

    /**
     * 计量维度组装（A6 §3）：role + stage + <b>iterationId</b>（有 OPEN 期才带）——
     * iterationId 取 run 发起时快照（dims 随事件落库，run 中途过门不追改）；期后修复
     * run 无 OPEN 期不带 iterationId——归项目不归期，收口期成本定格。写侧三处组装点
     * （dispatchTask / dispatchFixRun / TaskBackfillListener 回填续跑）共用本方法。
     */
    static Map<String, String> usageDims(String role, String stage, Iteration openIteration) {
        if (openIteration == null) {
            return Map.of(DIM_ROLE, role, DIM_STAGE, stage);
        }
        return Map.of(DIM_ROLE, role, DIM_STAGE, stage,
                DIM_ITERATION, openIteration.getId().toString());
    }

    /** role-assigned 发射（run 下发前——帧序 role-assigned → task-start → …）。 */
    private void emitRoleAssigned(Long projectId, String runId, RolePreset role, String stage,
                                  String engine) {
        streamAppService.publish(AgentEventTypes.ROLE_ASSIGNED, Map.of(
                AgentStreamAppService.PROJECT_FIELD, Long.toString(projectId),
                AgentStreamAppService.RUN_FIELD, runId,
                AgentEventTypes.ROLE_FIELD, role.name(),
                AgentEventTypes.ROLE_LABEL_FIELD, role.getName(),
                AgentEventTypes.ROLE_STAGE_FIELD, stage,
                AgentEventTypes.ROLE_ENGINE_FIELD, engine));
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }

}
