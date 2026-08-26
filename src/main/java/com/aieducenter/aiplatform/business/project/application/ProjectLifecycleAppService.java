package com.aieducenter.aiplatform.business.project.application;

import java.net.URI;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.EngineConfigAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.CreateProjectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectAgentTaskResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectCreatedResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectPreviewResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 项目生命周期用例（demo ProjectController.create/delete 的重写）：建项目 =
 * 工作区副作用先行落定 → 一事务建 Project + 第 1 期（seq=1 OPEN，起始 BA）→
 * SSE 通知（workspace-created + stage-changed）→ 前缀段自动开 BA 访谈（A3 §2.3
 * 「建项目即自动跑 BA（对话展开）」，编排对主链前缀的固定行为；#40 起走对话轨道）。
 *
 * <p>创建精简（#39，spec 0002 §3.1 一句话创建）：入参只剩 requirement——引擎读
 * 后台全局配置（#42）固化、类型单模板服务端缺省、项目名创建即落占位
 * {@link Project#PLACEHOLDER_NAME} 后由 {@link ProjectNamingAppService} 异步 LLM
 * 取名落位（响应不等取名，前端 invalidate 自然见到新名）。</p>
 *
 * <p>事务形态（照片1b workspace 的形态）：Docker 副作用在业务事务外先行，库记录
 * 收进短事务；落库失败回收已落定的工作区不留孤儿容器。删除真删级联（A3 §4）：
 * 工作区物理销毁（尽力而为，失败不阻断记录删除）→ prj_* 行级联 → SSE
 * workspace-destroyed（编排层发射制：副作用真实落定后，ADR-0001）。归档与源码包
 * 下载归本服务（动作与交付物）；读拼装（详情/列表/用量）归
 * {@link ProjectQueryAppService}。</p>
 */
@Service
@Slf4j
public class ProjectLifecycleAppService {

    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final BaInterviewAppService baInterviewAppService;
    private final EngineConfigAppService engineConfigAppService;
    private final ProjectRepository projectRepository;
    private final IterationRepository iterationRepository;
    private final ProjectQueryAppService queryAppService;
    private final PlatformNotificationAppService notificationAppService;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final ProjectNamingAppService namingService;
    private final TransactionTemplate transactionTemplate;

    public ProjectLifecycleAppService(WorkspaceLifecycleAppService workspaceLifecycleAppService,
                                      BaInterviewAppService baInterviewAppService,
                                      EngineConfigAppService engineConfigAppService,
                                      ProjectRepository projectRepository,
                                      IterationRepository iterationRepository,
                                      ProjectQueryAppService queryAppService,
                                      PlatformNotificationAppService notificationAppService,
                                      ProjectKnowledgeAppService knowledgeAppService,
                                      ProjectNamingAppService namingService,
                                      TransactionTemplate transactionTemplate) {
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.baInterviewAppService = baInterviewAppService;
        this.engineConfigAppService = engineConfigAppService;
        this.projectRepository = projectRepository;
        this.iterationRepository = iterationRepository;
        this.queryAppService = queryAppService;
        this.notificationAppService = notificationAppService;
        this.knowledgeAppService = knowledgeAppService;
        this.namingService = namingService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 建项目（#39：只传 requirement）：引擎 = 后台全局配置的生效引擎（#42，未配置
     * 回落注册表缺省）→ dev 工作区落定 → 一事务 Project（占位名 + 类型服务端缺省）
     * + 第 1 期（BA/OPEN/计数 0）→ SSE 双通知 → 异步 LLM 取名（不等结果，失败保
     * 占位）→ 自动开始 BA 访谈（#40 对话轨道，经 {@link BaInterviewAppService}）。
     * BA 起跑失败不回滚建项目（项目已成立，失败原因经 error 事件/日志表达）。
     */
    public ProjectCreatedResponse create(CreateProjectCommand command) {
        String engine = engineConfigAppService.activeEngineName();
        WorkspaceResponse workspace = workspaceLifecycleAppService
                .create(new CreateWorkspaceCommand(EnvKind.DEV));
        Project project;
        try {
            project = transactionTemplate.execute(status -> {
                Project saved = projectRepository.save(Project.create(
                        Project.PLACEHOLDER_NAME, null, engine,
                        Long.parseLong(workspace.workspaceId()), RequestContext.getUserId()));
                iterationRepository.save(Iteration.open(saved.getId(), Iteration.FIRST_SEQ,
                        ProjectMainChain.firstStage()));
                return saved;
            });
        } catch (RuntimeException e) {
            // 落库失败：回收已落定的工作区，不留与记录脱节的容器/网络/卷（照片1b 兜底）
            log.error("项目记录入库失败，回收工作区 {}", workspace.workspaceId(), e);
            destroyWorkspaceQuietly(workspace.workspaceId());
            throw e;
        }

        // SSE（副作用真实落定后发射，ADR-0001）
        notificationAppService.publish(ProjectEventTypes.WORKSPACE_CREATED, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, project.getId().toString(),
                ProjectEventTypes.PROJECT_NAME_FIELD, project.getName(),
                ProjectEventTypes.CONTAINER_FIELD, workspace.containerName(),
                ProjectEventTypes.PROJECT_TYPE_FIELD, project.getType().name(),
                ProjectEventTypes.ENGINE_FIELD, project.getEngine()));
        emitStageChanged(project.getId(), ProjectMainChain.firstStage());

        // 异步 LLM 取名（#39：占位名先落，取名后台完成落位；空 requirement 不取名）
        namingService.nameAsync(project.getId(), command.requirement());

        // 前缀段自动：建项目即开始 BA 访谈（#40 对话轨道：欢迎语 + 首个澄清问题挂
        // QUESTION 等待点经 SSE 触达；初始描述即开场输入）
        String prompt = command.requirement() == null || command.requirement().isBlank()
                ? RolePreset.DEFAULT_KICKOFF_PROMPT : command.requirement();
        ProjectAgentTaskResponse run;
        try {
            run = baInterviewAppService.runInterviewTurn(project.getId(), prompt);
        } catch (RuntimeException e) {
            log.warn("项目 {} 自动 BA 起跑失败（项目已成立，不回滚）", project.getId(), e);
            return new ProjectCreatedResponse(queryAppService.detail(project.getId()), null, false);
        }
        return new ProjectCreatedResponse(queryAppService.detail(project.getId()),
                run.runId(), run.accepted());
    }

    /**
     * 归档（A3 §4：单向终点——区别于开发中/已交付的派生投影）：落 archived_at，
     * 不迁移期、不清工作区（工具项目级常开，期后修复照常）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 重复归档（409）
     */
    public ProjectDetailResponse archive(Long projectId) {
        Project project = requireProject(projectId);
        project.archive(); // 单向不变量在聚合（重复归档 DomainException PRJ_013）
        projectRepository.save(project);
        return queryAppService.detail(projectId);
    }

    /**
     * 改名（#43 需求端右栏 inline 改名）：非生命周期动作——不设状态限制（归档项目
     * 照改），不发射 SSE（单账号场景，REST 响应即触达，前端 invalidate projects 域）。
     * 空白拒绝在聚合（PRJ_005 与建项目同口径）；长度上限归命令层（100）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_005 名空白（400，聚合抛出）
     */
    public ProjectDetailResponse rename(Long projectId, String name) {
        Project project = requireProject(projectId);
        project.rename(name); // 取名落位（#39）与用户改名（#43）共用同一行为
        projectRepository.save(project);
        return queryAppService.detail(projectId);
    }

    /**
     * 源码包（A3 §2.2 交付物 = 源码包 + 仓内文档，端点常开）：打包项目 dev 工作区
     * 为 tar.gz 字节流（排除 .env 机密与 node_modules）；文件名/HTTP 头归 REST 层。
     *
     * @throws ApplicationException PRJ_001 项目不存在；工作区故障 WSP_（容器已亡等）
     */
    public byte[] sourcePackage(Long projectId) {
        Project project = requireProject(projectId);
        return workspaceLifecycleAppService.packSource(Long.toString(project.getWorkspaceId()));
    }

    /**
     * 删除项目（真删级联）：工作区物理销毁（容器/网络/卷，尽力而为）→ prj_* 行
     * 级联删除（期随 FK 级联、确认留痕随期级联）→ knw_chunks 级联清理（A5 §5，
     * 尽力而为）→ SSE workspace-destroyed。
     */
    public void delete(Long projectId) {
        Project project = requireProject(projectId);
        destroyWorkspaceQuietly(Long.toString(project.getWorkspaceId()));
        transactionTemplate.executeWithoutResult(status -> {
            iterationRepository.deleteByProjectId(projectId);
            projectRepository.delete(project);
        });
        knowledgeAppService.purgeByProject(projectId);
        notificationAppService.publish(ProjectEventTypes.WORKSPACE_DESTROYED, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, projectId.toString()));
    }

    /**
     * 预览（B0 §6 步骤 4）：工作区端口真实暴露（docker publish，可访问 URL）→
     * SSE {@code preview-ready}（A1 §4 口子④——底座 PreviewReady 应用事件照发，
     * 业务呈现层补 projectId 后发射）→ 返回 URL。Demo 段产物可访问即预期效果，
     * 未起服务时 URL 返回连接拒绝属真实状态。
     */
    public ProjectPreviewResponse preview(Long projectId) {
        Project project = requireProject(projectId);
        URI url = workspaceLifecycleAppService
                .exposePreview(Long.toString(project.getWorkspaceId()));
        notificationAppService.publish(ProjectEventTypes.PREVIEW_READY, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, projectId.toString(),
                ProjectEventTypes.URL_FIELD, url.toString()));
        return new ProjectPreviewResponse(url.toString());
    }

    // ---------- 内部 ----------

    /** stage-changed 发射（建项目起始段 BA 的编排落位；门决策/DEV→TEST 见各自编排）。 */
    private void emitStageChanged(Long projectId, String stage) {
        notificationAppService.publish(ProjectEventTypes.STAGE_CHANGED,
                StageChangedPayload.plain(projectId, stage));
    }

    /** 工作区销毁（尽力而为）：失败记日志不阻断——真删级联优先，物理残留可重试销毁。 */
    private void destroyWorkspaceQuietly(String workspaceId) {
        try {
            workspaceLifecycleAppService.destroy(workspaceId);
        } catch (RuntimeException e) {
            log.warn("工作区 {} 物理销毁失败（记录照删，物理残留可重试销毁）：{}",
                    workspaceId, e.getMessage());
        }
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
