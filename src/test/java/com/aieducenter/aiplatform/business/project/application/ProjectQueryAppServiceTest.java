package com.aieducenter.aiplatform.business.project.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.metering.application.MeteringAppService;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.application.dto.response.GateReadyResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectUsageResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatusFilter;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.port.OpenBugQueryPort;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目读侧（片5c 验收 + #29，A3 §5 / A1 §2.5 / A6 §3）：详情 = 期位置 + 主链定义数据（进度条
 * 渲染）+ 门就绪（计数 ∧ 业务谓词，与 approve 门禁同口径）+ 派生状态；列表状态
 * 过滤 active/pending/archived/all；usage = 总量 + 平台成本（币种分桶 + 未配价标注）
 * + 分模型 + 分角色 + 按期。主链推进
 * 语义归 base.process StageAdvanceServiceTest，聚合口径（门就绪 ∧ 待办派生）在此。
 */
@SpringBootTest
class ProjectQueryAppServiceTest {

    @Autowired
    private ProjectQueryAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private IterationRepository iterationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** G3 业务谓词缝（#26 提供实现）；mock 以便断言谓词参与门就绪。 */
    @MockitoBean
    private OpenBugQueryPort openBugQueryPort;

    /** 跨项目待办查询面（pending 过滤的 AGENT_WAIT 半边）。 */
    @MockitoBean
    private AgentWaitAppService agentWaitAppService;

    /** 计量查询缝：mock 用例本体（MeteringLocalAdapter 是 sink+query 双端口 bean，整替会断 sink 注入）。 */
    @MockitoBean
    private MeteringAppService meteringAppService;

    /**
     * PRD 直读工作区的执行缝（#41）：mock exec 断言命令与出口码口径。get 按真实
     * 行为对不存在的工作区抛 WSP_001（previewUrlOf 的降级路径不变）。
     */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @BeforeEach
    void stubWorkspaceGetMissing() {
        when(workspaceLifecycleAppService.get(anyString()))
                .thenThrow(new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_iterations");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    // ---------- 详情：主链定义数据 + 门就绪 ----------

    @Test
    void given_any_project_when_detail_then_full_chain_stages_returned() {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 0, 8001L).getId();

        ProjectDetailResponse response = appService.detail(projectId);

        // 主链定义数据（A3 §5：阶段序列 + 终态标记，前端按数据渲染进度条）
        assertThat(response.stages()).extracting(ProjectDetailResponse.StageView::name)
                .containsExactly(ProjectMainChain.STAGE_BA, ProjectMainChain.STAGE_DEMO,
                        ProjectMainChain.STAGE_DEV, ProjectMainChain.STAGE_TEST,
                        ProjectMainChain.STAGE_ACCEPTANCE, ProjectMainChain.STAGE_CLOSED);
        assertThat(response.stages()).extracting(ProjectDetailResponse.StageView::label)
                .containsExactly("需求梳理", "Demo", "开发", "测试", "验收", "关闭");
        assertThat(response.stages()).extracting(ProjectDetailResponse.StageView::terminal)
                .containsExactly(false, false, false, false, false, true);
        // 开发段无门（推进归编排触发），四扇门 actor 就位
        assertThat(response.stages().get(2).gateActor()).isNull();
        assertThat(response.stages().get(0).gateActor()).isEqualTo(ProjectMainChain.GATE_ACTOR_USER);
        assertThat(response.stages().get(3).gateActor())
                .isEqualTo(ProjectMainChain.GATE_ACTOR_PLATFORM);
    }

    @Test
    void given_ba_without_task_when_detail_then_gate_user_not_ready() {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 0, 8002L).getId();

        ProjectDetailResponse response = appService.detail(projectId);

        assertThat(response.gate()).isEqualTo(new ProjectDetailResponse.GateView(
                ProjectMainChain.GATE_ACTOR_USER, false)); // 计数门禁不足
        verify(openBugQueryPort, never()).hasOpenBugs(any()); // 用户门不查 Bug 谓词
    }

    @Test
    void given_ba_with_task_without_prd_when_detail_then_gate_not_ready() {
        // #49 G1 业务谓词：计数达标（taskCount=1）但 PRD 未产出 → 不就绪
        // （门就绪与 approve 门禁同口径；PRD 产出前门不 ready）
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 1, 8002L,
                false).getId();

        ProjectDetailResponse response = appService.detail(projectId);

        assertThat(response.gate()).isEqualTo(new ProjectDetailResponse.GateView(
                ProjectMainChain.GATE_ACTOR_USER, false));
    }

    @Test
    void given_ba_with_task_when_detail_then_gate_user_ready() {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 1, 8003L,
                true).getId();

        ProjectDetailResponse response = appService.detail(projectId);

        assertThat(response.gate()).isEqualTo(new ProjectDetailResponse.GateView(
                ProjectMainChain.GATE_ACTOR_USER, true));
    }

    @Test
    void given_acceptance_without_task_when_detail_then_gate_ready_min_zero() {
        // 验收门 minTasks=0（验收段无 agent 任务，A3 §2.4）：无任务即就绪
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_ACCEPTANCE, 0, 8004L).getId();

        assertThat(appService.detail(projectId).gate()).isEqualTo(
                new ProjectDetailResponse.GateView(ProjectMainChain.GATE_ACTOR_USER, true));
    }

    @Test
    void given_test_with_task_and_open_bugs_when_detail_then_gate_platform_not_ready() {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_TEST, 1, 8005L).getId();
        when(openBugQueryPort.hasOpenBugs(projectId)).thenReturn(true);

        // G3 = 计数 ∧ 无未关闭 Bug（A3 §2.4 业务谓词）：计数达标、谓词不满足 → 未就绪
        assertThat(appService.detail(projectId).gate()).isEqualTo(
                new ProjectDetailResponse.GateView(ProjectMainChain.GATE_ACTOR_PLATFORM, false));
    }

    @Test
    void given_test_with_task_and_no_bugs_when_detail_then_gate_platform_ready() {
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_TEST, 1, 8006L).getId();
        when(openBugQueryPort.hasOpenBugs(projectId)).thenReturn(false);

        assertThat(appService.detail(projectId).gate()).isEqualTo(
                new ProjectDetailResponse.GateView(ProjectMainChain.GATE_ACTOR_PLATFORM, true));
    }

    @Test
    void given_dev_stage_when_detail_then_gate_null_no_gate() {
        // 开发段无门：推进由编排触发（首个测试任务），无按钮可点亮
        Long projectId = persistedProjectWithIteration(ProjectMainChain.STAGE_DEV, 3, 8007L).getId();

        assertThat(appService.detail(projectId).gate()).isNull();
    }

    @Test
    void given_closed_iteration_when_detail_then_stage_closed_delivered_gate_null() {
        Project project = projectRepository.save(Project
                .create("已收口", ProjectType.WEBSITE, "opencode", 8008L, null));
        Iteration iteration = iterationRepository.save(Iteration.open(project.getId(),
                Iteration.FIRST_SEQ, ProjectMainChain.STAGE_ACCEPTANCE));
        iteration.close(ProjectMainChain.STAGE_CLOSED);
        iterationRepository.save(iteration);

        ProjectDetailResponse response = appService.detail(project.getId());

        // 收口后的期位置回溯（A3 §5）：stage=CLOSED，派生已交付，无门
        assertThat(response.stage()).isEqualTo(ProjectMainChain.STAGE_CLOSED);
        assertThat(response.stageLabel()).isEqualTo("关闭");
        assertThat(response.status()).isEqualTo(ProjectStatus.DELIVERED);
        assertThat(response.stageTaskCount()).isNull();
        assertThat(response.gate()).isNull();
    }

    @Test
    void given_missing_project_when_detail_then_prj_001() {
        assertThatThrownBy(() -> appService.detail(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- 列表：状态过滤 ----------

    @Test
    void given_no_filter_when_list_then_desc_order_with_derived_status() {
        Project first = projectRepository.save(Project
                .create("老项目", ProjectType.WEBSITE, "opencode", 1L, null));
        iterationRepository.save(Iteration.open(first.getId(), 1, ProjectMainChain.STAGE_BA));
        projectRepository.save(Project
                .create("新项目（无期）", ProjectType.ECOMMERCE, "dsh", 2L, null));

        List<ProjectResponse> list = appService.list(null);

        // 有 OPEN 期 = 开发中；无 = 已交付（派生投影，A3 §1）
        assertThat(list).extracting(ProjectResponse::name)
                .containsExactly("新项目（无期）", "老项目");
        assertThat(list).extracting(ProjectResponse::status)
                .containsExactly(ProjectStatus.DELIVERED, ProjectStatus.IN_PROGRESS);
    }

    /**
     * 期选取语义护栏（查询收口后仍须成立）：currentOf = OPEN 优先、无 OPEN 取
     * max-seq——收口项目多闭期只展示最后一个（CLOSED 段），混入旧闭期不算数。
     */
    @Test
    void given_closed_iterations_only_when_list_then_max_seq_stage_shown() {
        Project delivered = projectRepository.save(Project
                .create("收口项目", ProjectType.WEBSITE, "opencode", 1L, null));
        Iteration earlier = Iteration.open(delivered.getId(), 1, ProjectMainChain.STAGE_BA);
        earlier.close(ProjectMainChain.STAGE_DEV);
        iterationRepository.save(earlier);
        Iteration latest = Iteration.open(delivered.getId(), 2, ProjectMainChain.STAGE_DEV);
        latest.close(ProjectMainChain.STAGE_TEST);
        iterationRepository.save(latest);

        List<ProjectResponse> list = appService.list(null);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).status()).isEqualTo(ProjectStatus.DELIVERED);
        assertThat(list.get(0).stage()).isEqualTo(ProjectMainChain.STAGE_TEST);
    }

    @Test
    void given_mixed_projects_when_list_active_then_only_open_unarchived() {
        Long active = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 0, 8101L).getId();
        persistedProjectWithoutIteration(8102L); // 无期 = 已交付
        Project archived = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 0, 8103L);
        archived.archive();
        projectRepository.save(archived);

        assertThat(appService.list(ProjectStatusFilter.ACTIVE)).extracting(ProjectResponse::id)
                .containsExactly(active.toString());
    }

    @Test
    void given_mixed_projects_when_list_archived_then_only_archived() {
        persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 0, 8104L);
        Project archived = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 0, 8105L);
        archived.archive();
        projectRepository.save(archived);

        assertThat(appService.list(ProjectStatusFilter.ARCHIVED)).extracting(ProjectResponse::id)
                .containsExactly(archived.getId().toString());
        assertThat(appService.list(ProjectStatusFilter.ARCHIVED))
                .allMatch(project -> project.status() == ProjectStatus.ARCHIVED);
    }

    @Test
    void given_gate_ready_or_pending_waits_when_list_pending_then_both_matched() {
        // ① 门就绪（GATE_PENDING 派生）：BA 计数 1 ∧ PRD 已产出
        Long gateReady = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 1, 8106L,
                true).getId();
        // ② 计数不足但有等待点（AGENT_WAIT）：工作区在待处理集合里
        Long waitPending = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 0, 8107L).getId();
        // ③ 两头都不占：不出现在 pending
        Long neither = persistedProjectWithIteration(ProjectMainChain.STAGE_DEV, 0, 8108L).getId();
        when(agentWaitAppService.pendingWorkspaceIds()).thenReturn(Set.of(8107L));

        assertThat(appService.list(ProjectStatusFilter.PENDING)).extracting(ProjectResponse::id)
                .containsExactlyInAnyOrder(gateReady.toString(), waitPending.toString())
                .doesNotContain(neither.toString());
    }

    @Test
    void given_archived_with_pending_work_when_list_pending_then_excluded() {
        // 归档是单向终点：在办视角（active/pending）不再出现归档项目
        Project archived = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 1, 8109L);
        archived.archive();
        projectRepository.save(archived);
        when(agentWaitAppService.pendingWorkspaceIds()).thenReturn(Set.of(8109L));

        assertThat(appService.list(ProjectStatusFilter.PENDING)).isEmpty();
    }

    // 非法过滤 code 的 400 PRJ_014 口径在端点层（ProjectControllerTest 兜底测试）；
    // 枚举签名后应用层不再可能收到非法值。

    // ---------- usage ----------

    @Test
    void given_usage_events_when_usage_then_total_cost_unpriced_by_model_by_role() {
        Long projectId = persistedProjectWithoutIteration(8201L).getId();
        TokenUsage tokens = new TokenUsage(100, 200, 30, 0, 0);
        when(meteringAppService.bySubject(eq(projectId.toString()), any(), any()))
                .thenReturn(new UsageSummary(projectId.toString(), null, null, tokens,
                        Map.of(Currency.getInstance("USD"), new BigDecimal("0.003"),
                                Currency.getInstance("CNY"), new BigDecimal("1.5")),
                        List.of(new UsageSummary.UnpricedUsage("testprov", "m-none",
                                TokenKind.INPUT)),
                        List.of(new UsageSummary.ModelUsage("deepseek", "deepseek-v4-pro",
                                tokens)),
                        List.of(new UsageSummary.DimUsage("role", "BA", tokens),
                                new UsageSummary.DimUsage("stage", "BA", tokens),
                                new UsageSummary.DimUsage("role", "DEV",
                                        new TokenUsage(1, 2, 0, 0, 0)))));

        ProjectUsageResponse response = appService.usage(projectId);

        assertThat(response.projectId()).isEqualTo(projectId.toString());
        assertThat(response.total()).isEqualTo(tokens); // 总量
        // 平台成本：Currency → 币种码字符串键，按键序稳定（CNY < USD）
        assertThat(response.cost().keySet()).containsExactly("CNY", "USD");
        assertThat(response.cost().get("USD")).isEqualByComparingTo(new BigDecimal("0.003"));
        assertThat(response.cost().get("CNY")).isEqualByComparingTo(new BigDecimal("1.5"));
        // 未配价标注：档位枚举 + 展示名随附（#34 房规）
        assertThat(response.unpriced()).containsExactly(
                new ProjectUsageResponse.UnpricedUsage("testprov", "m-none",
                        TokenKind.INPUT, "输入"));
        assertThat(response.byModel()).hasSize(1); // 分模型
        assertThat(response.byModel().get(0).model()).isEqualTo("deepseek-v4-pro");
        // 分角色 = dims.role 维度（stage 维度不进 byRole）
        assertThat(response.byRole()).extracting(ProjectUsageResponse.RoleUsage::role)
                .containsExactly("BA", "DEV");
        assertThat(response.byRole()).extracting(ProjectUsageResponse.RoleUsage::roleLabel)
                .containsExactly("需求分析师", "开发工程师");
        // 无 iterationId 维度 → 无按期桶
        assertThat(response.byIteration()).isEmpty();
    }

    @Test
    void given_iteration_dims_when_usage_then_by_iteration_with_seq_sorted() {
        // 两期（一期收口一期在跑）：seq 从库行补全；到期外的脏 dim 值 seq=null 排末位
        Project project = persistedProjectWithIteration(ProjectMainChain.STAGE_TEST, 0, 8202L);
        Iteration closed = iterationRepository
                .findByProjectIdAndStatus(project.getId(), IterationStatus.OPEN).orElseThrow();
        closed.close(ProjectMainChain.STAGE_CLOSED);
        iterationRepository.save(closed);
        Iteration open = iterationRepository.save(
                Iteration.open(project.getId(), 2, ProjectMainChain.STAGE_DEV));

        TokenUsage t1 = new TokenUsage(10, 5, 0, 0, 0);
        TokenUsage t2 = new TokenUsage(20, 6, 0, 0, 0);
        TokenUsage t3 = new TokenUsage(30, 7, 0, 0, 0);
        when(meteringAppService.bySubject(eq(project.getId().toString()), any(), any()))
                .thenReturn(new UsageSummary(project.getId().toString(), null, null,
                        new TokenUsage(60, 18, 0, 0, 0), Map.of(), List.of(), List.of(),
                        List.of(new UsageSummary.DimUsage("iterationId",
                                        open.getId().toString(), t2),
                                new UsageSummary.DimUsage("iterationId",
                                        closed.getId().toString(), t1),
                                new UsageSummary.DimUsage("iterationId", "not-a-number", t3),
                                new UsageSummary.DimUsage("role", "DEV",
                                        new TokenUsage(60, 18, 0, 0, 0)))));

        ProjectUsageResponse response = appService.usage(project.getId());

        // 按期聚合：期序号升序（数值序非字符串序），role 维度不进期桶
        assertThat(response.byIteration()).extracting(
                        ProjectUsageResponse.IterationUsage::iterationId)
                .containsExactly(closed.getId().toString(), open.getId().toString(),
                        "not-a-number");
        assertThat(response.byIteration()).extracting(
                        ProjectUsageResponse.IterationUsage::seq)
                .containsExactly(1, 2, null);
        assertThat(response.byIteration().get(0).tokens()).isEqualTo(t1);
        assertThat(response.byIteration().get(1).tokens()).isEqualTo(t2);
        // 期后修复口径的镜像：total 含全部（60），期桶合计也是全部带维度者的和——
        // 无 iterationId 的事件只进 total（该语义由编排侧不挂维度保证，此处验证过滤不虚构桶）
        assertThat(response.total().input()).isEqualTo(60);
    }

    @Test
    void given_no_events_when_usage_then_all_zero_not_error() {
        Long projectId = persistedProjectWithoutIteration(8203L).getId();
        when(meteringAppService.bySubject(eq(projectId.toString()), any(), any())).thenReturn(
                new UsageSummary(projectId.toString(), null, null,
                        new TokenUsage(0, 0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of()));

        ProjectUsageResponse response = appService.usage(projectId);

        assertThat(response.total().input()).isZero(); // 无事件全零而非错误（端口契约）
        assertThat(response.cost()).isEmpty();
        assertThat(response.unpriced()).isEmpty();
        assertThat(response.byModel()).isEmpty();
        assertThat(response.byRole()).isEmpty();
        assertThat(response.byIteration()).isEmpty();
    }

    @Test
    void given_missing_project_when_usage_then_prj_001() {
        assertThatThrownBy(() -> appService.usage(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- PRD 读（#41：直读工作区，文件是事实源） ----------

    @Test
    void given_workspace_prd_file_when_prd_then_content_and_file_mtime_returned() {
        Long projectId = persistedProjectWithoutIteration(8401L).getId();
        when(workspaceLifecycleAppService.exec(eq("8401"), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100000\n# 官网 PRD\n\n目标：三页官网。\n",
                        "", 0));

        PrdResponse response = appService.prd(projectId);

        // 一次 exec 取齐 mtime（stat 首行 epoch 秒）+ 正文（cat 余文），路径 = 主链产物单一事实
        assertThat(response.projectId()).isEqualTo(projectId.toString());
        assertThat(response.content()).isEqualTo("# 官网 PRD\n\n目标：三页官网。\n");
        assertThat(response.updatedAt()).isEqualTo(Instant.ofEpochSecond(1756100000));
        verify(workspaceLifecycleAppService).exec(eq("8401"), eq(new WorkspaceExecCommand(
                "test -f '/workspace/" + ProjectMainChain.PRD_ARTIFACT + "' && stat -c %Y "
                        + "'/workspace/" + ProjectMainChain.PRD_ARTIFACT + "' && cat "
                        + "'/workspace/" + ProjectMainChain.PRD_ARTIFACT + "'")));
    }

    @Test
    void given_no_prd_file_when_prd_then_prj_015_not_produced() {
        Long projectId = persistedProjectWithoutIteration(8402L).getId();
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("", "stat: 无法取文件状态", 1));

        // 文件缺（test -f 失败，退出码 1）= 未产出口径：404 PRJ_015（前端区分「还没产出」）
        assertThatThrownBy(() -> appService.prd(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PRD_NOT_PRODUCED.message());
    }

    @Test
    void given_dead_container_when_prd_then_wsp_002_environment_fault() {
        Long projectId = persistedProjectWithoutIteration(8403L).getId();
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("", "docker: No such container", 125));

        // docker exec 自身失败（125/126）≠ 未产出：环境故障照抛（WSP_002）
        assertThatThrownBy(() -> appService.prd(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.message());
    }

    @Test
    void given_missing_project_when_prd_then_prj_001() {
        assertThatThrownBy(() -> appService.prd(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_prd_status_bit_marked_when_saved_then_queryable_after_reload() {
        // 「PRD 已产出」状态位可查询（#49 G1 门谓词依赖）：置位落库往返不丢
        Project project = persistedProjectWithoutIteration(8404L);
        project.markPrdProduced();
        projectRepository.save(project);

        assertThat(projectRepository.findById(project.getId()).orElseThrow()
                .getPrdProducedAt()).isNotNull();
        assertThat(projectRepository.findById(
                persistedProjectWithoutIteration(8405L).getId()).orElseThrow()
                .getPrdProducedAt()).isNull(); // 未置位项目仍为 NULL
    }

    // ---------- workbench 查询端口：门就绪清单 / workspaceId 寻址 ----------

    @Test
    void given_gate_ready_projects_when_listGateReady_then_ready_only_with_stage_label() {
        // ① BA 计数达标 ∧ PRD 已产出（G1 用户门就绪）→ 在列
        Project ready = persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 1, 8301L, true);
        // ② 计数不足 → 不在列
        persistedProjectWithIteration(ProjectMainChain.STAGE_BA, 0, 8302L);
        // ③ 开发段无门 → 不在列
        persistedProjectWithIteration(ProjectMainChain.STAGE_DEV, 3, 8303L);
        // ④ 已归档（门就绪也排除：单向终点，在办视角）→ 不在列
        Project archived = persistedProjectWithIteration(ProjectMainChain.STAGE_ACCEPTANCE,
                0, 8304L);
        archived.archive();
        projectRepository.save(archived);

        List<GateReadyResponse> readyList = appService.listGateReady();

        assertThat(readyList).hasSize(1);
        GateReadyResponse entry = readyList.get(0);
        assertThat(entry.projectId()).isEqualTo(ready.getId().toString());
        assertThat(entry.stageLabel()).isEqualTo("需求梳理");
        assertThat(entry.gateActor()).isEqualTo(ProjectMainChain.GATE_ACTOR_USER);
        // since = 期最近一次变更（门就绪时刻的近似锚点），与期的审计 updatedAt 对齐
        Iteration iteration = iterationRepository.findByProjectId(ready.getId()).get(0);
        assertThat(entry.readySince()).isEqualTo(iteration.getUpdatedAt()
                .atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void given_platform_gate_with_open_bugs_when_listGateReady_then_excluded() {
        // G3 业务谓词（计数 ∧ 无未关闭 Bug）：有 Bug → 未就绪，不在列
        persistedProjectWithIteration(ProjectMainChain.STAGE_TEST, 1, 8305L);
        when(openBugQueryPort.hasOpenBugs(any())).thenReturn(true);

        assertThat(appService.listGateReady()).isEmpty();
    }

    @Test
    void given_workspace_ids_when_projectIdByWorkspaceId_then_only_known_mapped() {
        Long projectId = persistedProjectWithoutIteration(8306L).getId();

        Map<Long, String> mapped = appService.projectIdByWorkspaceId(Set.of(8306L, 9999L));

        assertThat(mapped).containsEntry(8306L, projectId.toString());
        assertThat(mapped).doesNotContainKey(9999L); // 工作区无项目：不映射
        assertThat(appService.projectIdByWorkspaceId(Set.of())).isEmpty(); // 空入参安全
    }

    // ---------- 测试数据 ----------

    private Project persistedProjectWithIteration(String stage, int taskCount, long workspaceId) {
        return persistedProjectWithIteration(stage, taskCount, workspaceId, false);
    }

    /** prdProduced：置「PRD 已产出」状态位（#49 G1 门就绪谓词的另一半输入）。 */
    private Project persistedProjectWithIteration(String stage, int taskCount, long workspaceId,
            boolean prdProduced) {
        Project project = Project.create("读侧测试", ProjectType.WEBSITE, "opencode",
                workspaceId, null);
        if (prdProduced) {
            project.markPrdProduced();
        }
        projectRepository.save(project);
        Iteration iteration = Iteration.open(project.getId(), Iteration.FIRST_SEQ, stage);
        for (int i = 0; i < taskCount; i++) {
            iteration.recordStageTask();
        }
        iterationRepository.save(iteration);
        return project;
    }

    private Project persistedProjectWithoutIteration(long workspaceId) {
        return projectRepository.save(Project
                .create("读侧测试（无期）", ProjectType.WEBSITE, "opencode", workspaceId, null));
    }
}
