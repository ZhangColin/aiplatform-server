package com.aieducenter.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageQueryPort;
import com.aieducenter.aiplatform.base.process.domain.model.ExitGate;
import com.aieducenter.aiplatform.base.process.domain.model.StageEntry;
import com.aieducenter.aiplatform.base.process.domain.service.StageAdvanceService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.application.dto.response.GateReadyResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectBriefResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectUsageResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.port.OpenBugQueryPort;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 项目读侧用例（片5c，A3 §5 / A1 §2.5）：详情（期位置 + 主链定义数据 + 门就绪 +
 * 派生状态）、列表（状态过滤 active/pending/archived/缺省 all）、用量（总量 +
 * 分模型 + 分角色）+ workbench 查询端口（门就绪清单 / workspaceId 寻址，A2 §5）。
 * 写侧（生命周期/门操作/需求池）各自成服务，读拼装集中一处——门就绪的裁决
 * （计数 ∧ 业务谓词）与列表 pending 派生（期门就绪 ∨ 工作区待处理等待点，A2 §63）
 * 同源，避免两处口径漂移。
 */
@Service
public class ProjectQueryAppService {

    /** 列表状态过滤的合法取值（A2 §63 + A3 §4 补 archived；缺省 all）。 */
    private static final String FILTER_ACTIVE = "active";
    private static final String FILTER_PENDING = "pending";
    private static final String FILTER_ARCHIVED = "archived";

    /** 任务下发时记入 dims 的角色维度键（与 ProjectAgentTaskAppService 对齐）。 */
    private static final String DIM_ROLE = "role";

    private final ProjectRepository projectRepository;
    private final IterationRepository iterationRepository;
    private final StageAdvanceService stageAdvanceService;
    private final OpenBugQueryPort openBugQueryPort;
    private final AgentWaitAppService agentWaitAppService;
    private final UsageQueryPort usageQueryPort;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public ProjectQueryAppService(ProjectRepository projectRepository,
                                  IterationRepository iterationRepository,
                                  StageAdvanceService stageAdvanceService,
                                  OpenBugQueryPort openBugQueryPort,
                                  AgentWaitAppService agentWaitAppService,
                                  UsageQueryPort usageQueryPort,
                                  WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.projectRepository = projectRepository;
        this.iterationRepository = iterationRepository;
        this.stageAdvanceService = stageAdvanceService;
        this.openBugQueryPort = openBugQueryPort;
        this.agentWaitAppService = agentWaitAppService;
        this.usageQueryPort = usageQueryPort;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    /**
     * 项目详情（A3 §5：期位置 + 主链定义数据 + 门就绪 + 派生状态——足够前端
     * 渲染进度条与点亮按钮）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public ProjectDetailResponse detail(Long projectId) {
        Project project = loadProject(projectId);
        Iteration iteration = Iteration
                .currentOf(iterationRepository.findByProjectId(projectId)).orElse(null);
        return toDetail(project, iteration, gateView(projectId, iteration));
    }

    /**
     * 项目列表（创建时间倒序）+ 状态过滤：{@code active}（未归档 ∧ 有 OPEN 期）、
     * {@code pending}（未归档 ∧ 存在 dev 待办：期门就绪 ∨ 工作区待处理等待点）、
     * {@code archived}（已归档）；缺省 all。
     *
     * @throws ApplicationException PRJ_014 过滤参数不合法
     */
    public List<ProjectResponse> list(String status) {
        String filter = normalizeFilter(status);
        // pending 一次取全量待处理工作区（跨项目待办查询面，A2 §60），不在循环里逐项目查
        Set<Long> pendingWorkspaces = FILTER_PENDING.equals(filter)
                ? agentWaitAppService.pendingWorkspaceIds()
                : Set.of();
        Map<Long, List<Iteration>> iterationsByProject = iterationsByProject();
        return projectsNewestFirst().stream()
                .filter(project -> matches(project, iterationsByProject.get(project.getId()),
                        filter, pendingWorkspaces))
                .map(project -> toResponse(project, Iteration
                        .currentOf(iterationsByProject.get(project.getId())).orElse(null)))
                .toList();
    }

    /**
     * 项目用量（A1 §2.5 基础版）：经计量查询端口按 subject=projectId 聚合——
     * 总量 + 分模型 + 分角色（dims.role 维度过滤）；cost 与按期聚合随 A6 扩展。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public ProjectUsageResponse usage(Long projectId) {
        loadProject(projectId);
        UsageSummary summary = usageQueryPort.bySubject(Long.toString(projectId), null, null);
        List<ProjectUsageResponse.ModelUsage> byModel = summary.byModel().stream()
                .map(model -> new ProjectUsageResponse.ModelUsage(
                        model.provider(), model.model(), model.tokens()))
                .toList();
        List<ProjectUsageResponse.RoleUsage> byRole = summary.byDims().stream()
                .filter(dim -> DIM_ROLE.equals(dim.dimKey()))
                .map(dim -> new ProjectUsageResponse.RoleUsage(dim.dimValue(),
                        RolePreset.byName(dim.dimValue()).map(RolePreset::getName).orElse(null),
                        dim.tokens()))
                .toList();
        return new ProjectUsageResponse(Long.toString(projectId), summary.total(),
                byModel, byRole);
    }

    // ---------- workbench 查询端口（A2 §5） ----------

    /**
     * 门就绪项目清单（workbench GATE_PENDING 待办投影源）：期 OPEN ∧ 当前阶段
     * 有门 ∧ 门禁满足（{@link #detail} 的 gate 视图同一裁决口径，两处不漂移）。
     * 归档项目不在列（单向终点，在办视角排除）；创建时间倒序。
     */
    public List<GateReadyResponse> listGateReady() {
        Map<Long, List<Iteration>> iterationsByProject = iterationsByProject();
        return projectsNewestFirst().stream()
                .filter(project -> project.getArchivedAt() == null)
                .map(project -> gateReadyOf(project, Iteration
                        .currentOf(iterationsByProject.get(project.getId())).orElse(null)))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * workspaceId → projectId 寻址（workbench AGENT_WAIT 待办投影：等待点挂工作区，
     * 待办以项目寻址）。无对应项目的工作区（非 dev 环境 / 项目已删的残留等待点）
     * 不在返回——其待办无处导航，投影层自会跳过。
     */
    public Map<Long, String> projectIdByWorkspaceId(Collection<Long> workspaceIds) {
        if (workspaceIds == null || workspaceIds.isEmpty()) {
            return Map.of();
        }
        return projectRepository.findByWorkspaceIdIn(workspaceIds).stream()
                .collect(Collectors.toMap(Project::getWorkspaceId,
                        project -> project.getId().toString()));
    }

    /**
     * 项目简报批查（task BC opc 任务卡片的最小项目上下文，A4 §7）：项目名 +
     * 预览地址（工作区记录的 previewPort 派生——示意级预览 URL 形如
     * {@code http://localhost:{port}/}，与 EnvironmentBackend.exposePort 同式，
     * 此处零副作用只读派生）。不存在的项目（已删残留）不在 Map。
     */
    public Map<Long, ProjectBriefResponse> projectBriefs(Collection<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Map.of();
        }
        return projectRepository.findAllById(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, this::briefOf));
    }

    /**
     * 项目归属账号（task BC 详情的 opc/dev 谓词半边：assignee ∨ owner，
     * A4 §7）。项目不存在返回 null。
     */
    public Long ownerAccountIdOf(Long projectId) {
        return projectRepository.findById(projectId)
                .map(Project::getOwnerAccountId)
                .orElse(null);
    }

    /**
     * 项目存在性把关（task BC 读侧入口共用，PRJ_001 同码）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public void requireProject(Long projectId) {
        loadProject(projectId);
    }

    /**
     * projectId 字符串寻址解析 + 存在性把关（task BC 的项目路径端点收口——跨
     * 上下文经应用层，端点层工具不外借；非数值/非正数同 PRJ_001，404 语义）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public Long requireProjectId(String projectId) {
        try {
            long parsed = Long.parseLong(projectId);
            if (parsed > 0) {
                return loadProject(parsed).getId();
            }
        } catch (NumberFormatException ignored) {
            // 非数值 → 落到下方统一 404
        }
        throw new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND);
    }

    // ---------- 全量读装载（列表与门就绪清单共用前奏） ----------

    /** 简报拼装：项目名 + 预览地址（previewPort 派生，零副作用）；工作区记录
     * 已亡的残留项目预览地址置 null（卡片仍可导航，不因读简报炸列表）。 */
    private ProjectBriefResponse briefOf(Project project) {
        return new ProjectBriefResponse(project.getId().toString(), project.getName(),
                previewUrlOf(project.getWorkspaceId()));
    }

    /** 预览地址派生（与 EnvironmentBackend.exposePort 同式，零副作用）；工作区
     * 记录已亡（WSP_001）置 null，其余异常照抛。 */
    private String previewUrlOf(Long workspaceId) {
        try {
            int previewPort = workspaceLifecycleAppService
                    .get(Long.toString(workspaceId)).previewPort();
            return "http://localhost:" + previewPort + "/";
        } catch (ApplicationException e) {
            if (!WorkspaceMessage.WORKSPACE_NOT_FOUND.code()
                    .equals(e.getCodeMessage().code())) {
                throw e;
            }
            return null;
        }
    }

    /** 期按项目分组（全量读：量小不分页，列表/门就绪清单一次装载共用）。 */
    private Map<Long, List<Iteration>> iterationsByProject() {
        return iterationRepository.findAll().stream()
                .collect(Collectors.groupingBy(Iteration::getProjectId));
    }

    /** 全量项目，创建时间倒序。 */
    private List<Project> projectsNewestFirst() {
        return projectRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    // ---------- 门就绪（详情与列表 pending 派生共用的唯一口径） ----------

    /**
     * 当前阶段门就绪（A3 §5：计数门禁 ∧ 业务谓词）：无 OPEN 期 / 终态 / 无门段
     * 返回 null（无按钮可点亮）；G3（actor=开发平台）另 ∧ 无未关闭 Bug。
     */
    private ProjectDetailResponse.GateView gateView(Long projectId, Iteration iteration) {
        if (iteration == null || iteration.getStatus() != IterationStatus.OPEN) {
            return null;
        }
        StageEntry stage = ProjectMainChain.definition().find(iteration.getStage()).orElse(null);
        if (stage == null || stage.terminal() || stage.exitGate() == null) {
            return null;
        }
        ExitGate gate = stage.exitGate();
        boolean ready = stageAdvanceService.gateOpen(ProjectMainChain.definition(),
                iteration.getStage(), iteration.getStageTaskCount());
        if (ready && ProjectMainChain.GATE_ACTOR_PLATFORM.equals(gate.actor())) {
            ready = !openBugQueryPort.hasOpenBugs(projectId);
        }
        return new ProjectDetailResponse.GateView(gate.actor(), ready);
    }

    /** 门就绪待办条目（未就绪 / 无门 / 已收口 → null）：title 素材（阶段标签）与时刻在此取齐。 */
    private GateReadyResponse gateReadyOf(Project project, Iteration iteration) {
        ProjectDetailResponse.GateView gate = gateView(project.getId(), iteration);
        if (gate == null || !gate.ready()) {
            return null;
        }
        StageEntry stage = ProjectMainChain.definition().find(iteration.getStage()).orElseThrow();
        LocalDateTime since = iteration.getUpdatedAt() != null
                ? iteration.getUpdatedAt() : iteration.getCreatedAt();
        return new GateReadyResponse(project.getId().toString(), stage.label(), gate.actor(),
                since.atZone(ZoneId.systemDefault()).toInstant());
    }

    // ---------- 列表过滤 ----------

    /** 过滤参数归一（空白 = 缺省 all）；不合法取值 400 PRJ_014。 */
    private static String normalizeFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String filter = status.strip().toLowerCase(Locale.ROOT);
        if (!FILTER_ACTIVE.equals(filter) && !FILTER_PENDING.equals(filter)
                && !FILTER_ARCHIVED.equals(filter)) {
            throw new ApplicationException(ProjectMessage.PROJECT_FILTER_UNKNOWN, status);
        }
        return filter;
    }

    private boolean matches(Project project, List<Iteration> iterations, String filter,
                            Set<Long> pendingWorkspaces) {
        if (filter == null) {
            return true;
        }
        if (FILTER_ARCHIVED.equals(filter)) {
            return project.getArchivedAt() != null;
        }
        // active/pending 都是「在办」视角：归档项目不再出现（归档是单向终点，A3 §4）
        if (project.getArchivedAt() != null) {
            return false;
        }
        if (FILTER_ACTIVE.equals(filter)) {
            return Iteration.currentOf(iterations)
                    .map(iteration -> iteration.getStatus() == IterationStatus.OPEN)
                    .orElse(false);
        }
        Iteration current = Iteration.currentOf(iterations).orElse(null);
        ProjectDetailResponse.GateView gate = gateView(project.getId(), current);
        return (gate != null && gate.ready())
                || pendingWorkspaces.contains(project.getWorkspaceId());
    }

    // ---------- 响应拼装 ----------

    /** 详情拼装：列表字段 + 主链定义数据 + 门就绪。 */
    private ProjectDetailResponse toDetail(Project project, Iteration iteration,
                                           ProjectDetailResponse.GateView gate) {
        ProjectResponse base = toResponse(project, iteration);
        List<ProjectDetailResponse.StageView> stages = ProjectMainChain.definition().stages()
                .stream()
                .map(stage -> new ProjectDetailResponse.StageView(stage.name(), stage.label(),
                        stage.defaultRole(),
                        stage.exitGate() != null ? stage.exitGate().actor() : null,
                        stage.terminal()))
                .toList();
        return new ProjectDetailResponse(base.id(), base.name(), base.type(), base.typeName(),
                base.engine(), base.workspaceId(), base.stage(), base.stageLabel(),
                base.status(), base.statusLabel(), base.stageTaskCount(), base.archived(),
                base.createdAt(), stages, gate);
    }

    /** 列表项拼装：期位置（stage/标签；收口后为 CLOSED）+ 派生项目状态（归档 >
     * 开发中/已交付，A3 §4 三态）+ 计数（收口后不展示——门禁输入，过程已结束）。 */
    private ProjectResponse toResponse(Project project, Iteration iteration) {
        boolean archived = project.getArchivedAt() != null;
        boolean open = !archived && iteration != null
                && iteration.getStatus() == IterationStatus.OPEN;
        String stage = iteration != null ? iteration.getStage() : null;
        String stageLabel = stage != null
                ? ProjectMainChain.definition().find(stage).map(StageEntry::label).orElse(null)
                : null;
        return new ProjectResponse(
                project.getId().toString(),
                project.getName(),
                project.getType(),
                project.getType().getName(),
                project.getEngine(),
                project.getWorkspaceId().toString(),
                stage,
                stageLabel,
                archived ? ProjectResponse.STATUS_ARCHIVED
                        : open ? ProjectResponse.STATUS_IN_PROGRESS
                                : ProjectResponse.STATUS_DELIVERED,
                archived ? "已归档" : open ? "开发中" : "已交付",
                open ? iteration.getStageTaskCount() : null,
                archived,
                project.getCreatedAt());
    }

    private Project loadProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
