package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.format.FormatterRegistry;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;
import com.cartisan.web.config.BaseEnumConverter;
import com.cartisan.web.exception.GlobalExceptionHandler;

import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.business.project.application.ProjectDemandPoolAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectGateAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.AddDemandEntryCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.DemandPoolEntryResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectCreatedResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectPreviewResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectUsageResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.DemandEntryKind;
import com.aieducenter.aiplatform.business.project.domain.enums.DemandSource;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatusFilter;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目 REST 面（swagger 契约验收）：ApiResponse 信封、建项目响应携带自动 BA
 * runId、类型 Integer code 双向、PRJ_001 → 404、参数校验；片5c 端点（需求池/
 * 归档/用量/源码包/列表过滤）的契约面。
 */
@WebMvcTest(ProjectController.class)
@Import({ProjectControllerTest.ExceptionAdviceConfig.class,
        ProjectControllerTest.EnumParamBindingConfig.class,
        com.cartisan.web.config.JacksonConfiguration.class})
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectLifecycleAppService appService;

    @MockitoBean
    private ProjectGateAppService gateAppService;

    @MockitoBean
    private ProjectQueryAppService queryAppService;

    @MockitoBean
    private ProjectDemandPoolAppService demandPoolAppService;

    /** A2 起全 /api/** 拦截——MVC 契约测试不走登录链，夹具直接注 RequestContext。 */
    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 1L, "project-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_valid_command_when_create_then_wrapped_with_ba_run() throws Exception {
        when(appService.create(any())).thenReturn(
                new ProjectCreatedResponse(detailOf("100", ProjectStatus.IN_PROGRESS,
                        "BA", 0, false), "run-1", true));

        // #39 一句话创建：请求体只传 requirement（name/type/engine 从契约面消失）
        performAsUser(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"做一个官网\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.project.id").value("100"))
                .andExpect(jsonPath("$.data.project.type").value(1)) // BaseEnum → Integer code
                .andExpect(jsonPath("$.data.project.stage").value("BA"))
                .andExpect(jsonPath("$.data.project.status").value(1)) // IN_PROGRESS → code
                .andExpect(jsonPath("$.data.project.statusName").value("开发中"))
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.accepted").value(true));
        verify(appService).create(argThat(cmd -> "做一个官网".equals(cmd.requirement())));
    }

    @Test
    void given_oversized_requirement_when_create_then_rejected_as_400() throws Exception {
        // requirement 是创建唯一入参（可空）；长度上限 5000 仍守门
        performAsUser(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"" + "长".repeat(5001) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void given_projects_when_list_then_wrapped_array() throws Exception {
        when(queryAppService.list(null)).thenReturn(List.of(new ProjectResponse("100", "官网",
                ProjectType.WEBSITE, "官网", "opencode", "900", null, null,
                ProjectStatus.DELIVERED, "已交付", null, false, null)));

        performAsUser(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("100"))
                .andExpect(jsonPath("$.data[0].status").value(2)) // DELIVERED → code
                .andExpect(jsonPath("$.data[0].statusName").value("已交付"));
    }

    @Test
    void given_status_filter_when_list_then_passed_through() throws Exception {
        when(queryAppService.list(ProjectStatusFilter.ACTIVE)).thenReturn(List.of());

        performAsUser(get("/api/projects").param("status", "1")) // ACTIVE → Integer code
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        verify(queryAppService).list(ProjectStatusFilter.ACTIVE);
    }

    @Test
    void given_unknown_filter_when_list_then_prj_014_as_400() throws Exception {
        // 非法取值（未知 code / 非数值）在绑定层即 400 PRJ_014，应用服务不被触达
        performAsUser(get("/api/projects").param("status", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("无效的项目列表状态过滤参数"));
        performAsUser(get("/api/projects").param("status", "active"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无效的项目列表状态过滤参数"));
        verify(queryAppService, never()).list(any());
    }

    @Test
    void given_detail_when_get_then_stages_and_gate_returned() throws Exception {
        when(queryAppService.detail(100L)).thenReturn(
                new ProjectDetailResponse("100", "官网 demo", ProjectType.WEBSITE, "官网",
                        "opencode", "900", "BA", "需求梳理",
                        ProjectStatus.IN_PROGRESS, "开发中", 0, false,
                        LocalDateTime.of(2026, 8, 22, 10, 0),
                        List.of(new ProjectDetailResponse.StageView("BA", "需求梳理", "BA",
                                "USER", false),
                                new ProjectDetailResponse.StageView("CLOSED", "关闭", null,
                                        null, true)),
                        new ProjectDetailResponse.GateView("USER", false)));

        performAsUser(get("/api/projects/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("BA"))
                .andExpect(jsonPath("$.data.stages[0].name").value("BA"))
                .andExpect(jsonPath("$.data.stages[0].terminal").value(false))
                .andExpect(jsonPath("$.data.stages[1].terminal").value(true))
                .andExpect(jsonPath("$.data.gate.actor").value("USER"))
                .andExpect(jsonPath("$.data.gate.ready").value(false));
    }

    @Test
    void given_unknown_project_when_get_then_prj_001_mapped_to_404() throws Exception {
        when(queryAppService.detail(404L))
                .thenThrow(new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));

        performAsUser(get("/api/projects/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void given_non_numeric_id_when_get_then_404() throws Exception {
        performAsUser(get("/api/projects/abc"))
                .andExpect(status().isNotFound());
    }

    @Test
    void given_project_when_delete_then_ok_without_data() throws Exception {
        performAsUser(delete("/api/projects/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(appService).delete(100L);
    }

    @Test
    void given_gate_ready_when_approve_then_advanced_project_returned() throws Exception {
        when(gateAppService.approve(100L)).thenReturn(
                detailOf("100", ProjectStatus.IN_PROGRESS, "DEMO", 0, false));

        performAsUser(post("/api/projects/100/stage/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("DEMO"))
                .andExpect(jsonPath("$.data.stageTaskCount").value(0));
        verify(gateAppService).approve(100L);
    }

    @Test
    void given_gate_blocked_when_approve_then_mapped_to_409() throws Exception {
        when(gateAppService.approve(100L)).thenThrow(
                new ApplicationException(ProjectMessage.GATE_TASKS_INSUFFICIENT));

        performAsUser(post("/api/projects/100/stage/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("门禁不足：本阶段完成任务数未达门限"));
    }

    @Test
    void given_reason_when_reject_then_passed_through() throws Exception {
        when(gateAppService.reject(eq(100L), argThat("布局不对"::equals), eq(false)))
                .thenReturn(detailOf("100", ProjectStatus.IN_PROGRESS,
                        "BA", 1, false));

        performAsUser(post("/api/projects/100/stage/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"布局不对\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("BA"));
    }

    @Test
    void given_requirement_change_flag_when_reject_then_flag_passed_through() throws Exception {
        // #46：requirementChange 缺省 false（缺字段即 false）；显式 true 原样透传——
        // G2 表单的「涉及需求变更」标记是 BA 联动的唯一开关
        performAsUser(post("/api/projects/100/stage/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"布局不对\"}"))
                .andExpect(status().isOk());
        performAsUser(post("/api/projects/100/stage/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"布局不对\",\"requirementChange\":true}"))
                .andExpect(status().isOk());
        verify(gateAppService).reject(eq(100L), argThat("布局不对"::equals), eq(false));
        verify(gateAppService).reject(eq(100L), argThat("布局不对"::equals), eq(true));
    }

    @Test
    void given_blank_reason_when_reject_then_rejected_as_400() throws Exception {
        performAsUser(post("/api/projects/100/stage/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        verify(gateAppService, never()).reject(any(), any(), anyBoolean());
    }

    @Test
    void given_entry_when_add_demand_then_wrapped() throws Exception {
        when(demandPoolAppService.add(eq(100L), any(AddDemandEntryCommand.class))).thenReturn(
                new DemandPoolEntryResponse("700", "支持暗黑模式", DemandEntryKind.REQUIREMENT,
                        "需求", DemandSource.USER, "用户", 1L,
                        LocalDateTime.of(2026, 8, 22, 12, 0)));

        performAsUser(post("/api/projects/100/demand-pool")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"支持暗黑模式\",\"kind\":1}")) // BaseEnum → Integer code 双向
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("支持暗黑模式"))
                .andExpect(jsonPath("$.data.kind").value(1))
                .andExpect(jsonPath("$.data.kindName").value("需求"))
                .andExpect(jsonPath("$.data.source").value(1))
                .andExpect(jsonPath("$.data.sourceName").value("用户"));
        verify(demandPoolAppService).add(eq(100L), argThat(cmd ->
                "支持暗黑模式".equals(cmd.content()) && cmd.kind() == DemandEntryKind.REQUIREMENT
                        && cmd.source() == null));
    }

    @Test
    void given_blank_content_when_add_demand_then_rejected_as_400() throws Exception {
        performAsUser(post("/api/projects/100/demand-pool")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\" \"}"))
                .andExpect(status().isBadRequest());
        verify(demandPoolAppService, never()).add(any(), any());
    }

    @Test
    void given_entries_when_list_demand_then_wrapped_array_new_first() throws Exception {
        when(demandPoolAppService.entries(100L)).thenReturn(List.of(
                new DemandPoolEntryResponse("701", "第二条", null, null, DemandSource.TEST,
                        "测试", null, LocalDateTime.of(2026, 8, 22, 12, 1)),
                new DemandPoolEntryResponse("700", "第一条", DemandEntryKind.BUG, "缺陷",
                        DemandSource.USER, "用户", 1L, LocalDateTime.of(2026, 8, 22, 12, 0))));

        performAsUser(get("/api/projects/100/demand-pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].content").value("第二条"))
                .andExpect(jsonPath("$.data[0].kind").value(nullValue())) // 未分类条目 kind=null
                .andExpect(jsonPath("$.data[1].kind").value(2));
    }

    @Test
    void given_unarchived_when_archive_then_detail_returned() throws Exception {
        when(appService.archive(100L)).thenReturn(
                detailOf("100", ProjectStatus.ARCHIVED, "BA", 1, true));

        performAsUser(post("/api/projects/100/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(3)) // ARCHIVED → code
                .andExpect(jsonPath("$.data.statusName").value("已归档"))
                .andExpect(jsonPath("$.data.archived").value(true));
    }

    @Test
    void given_already_archived_when_archive_again_then_prj_013_as_409() throws Exception {
        // 真实路径是聚合不变量的 DomainException（CartisanException 统一按 CodeMessage 映射 409）
        when(appService.archive(100L)).thenThrow(
                new DomainException(ProjectMessage.PROJECT_ALREADY_ARCHIVED));

        performAsUser(post("/api/projects/100/archive"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("项目已归档（归档是单向终点）"));
    }

    @Test
    void given_valid_name_when_rename_then_detail_returned() throws Exception {
        // #43：动作端点风格同 archive；响应与详情端点同构（前端 invalidate 后刷新列表/顶栏）
        when(appService.rename(100L, "品牌官网")).thenReturn(
                new ProjectDetailResponse("100", "品牌官网", ProjectType.WEBSITE, "官网",
                        "opencode", "900", "BA", "需求梳理",
                        ProjectStatus.IN_PROGRESS, "开发中", 0, false,
                        LocalDateTime.of(2026, 8, 22, 10, 0), List.of(), null));

        performAsUser(post("/api/projects/100/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"品牌官网\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("品牌官网"))
                .andExpect(jsonPath("$.data.stage").value("BA"));
        verify(appService).rename(100L, "品牌官网");
    }

    @Test
    void given_oversized_name_when_rename_then_rejected_as_400() throws Exception {
        // 长度上限 100 归命令层守门（与 #39 取名净化同限，DB 列长同源）
        performAsUser(post("/api/projects/100/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "长".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(appService, never()).rename(any(), any());
    }

    @Test
    void given_blank_name_when_rename_then_prj_005_as_400() throws Exception {
        // 空白拒绝在聚合（PRJ_005，与建项目同口径——DomainException 按 CodeMessage 映射 400）
        when(appService.rename(eq(100L), argThat(" "::equals)))
                .thenThrow(new DomainException(ProjectMessage.PROJECT_NAME_BLANK));

        performAsUser(post("/api/projects/100/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("项目名不能为空白"));
    }

    @Test
    void given_unknown_project_when_rename_then_prj_001_mapped_to_404() throws Exception {
        when(appService.rename(404L, "任意名"))
                .thenThrow(new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));

        performAsUser(post("/api/projects/404/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"任意名\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void given_usage_when_get_then_aggregations_returned() throws Exception {
        TokenUsage tokens = new TokenUsage(100, 200, 30, 0, 0);
        when(queryAppService.usage(100L)).thenReturn(new ProjectUsageResponse("100", tokens,
                Map.of("USD", new BigDecimal("0.003")),
                List.of(new ProjectUsageResponse.UnpricedUsage("testprov", "m-none",
                        TokenKind.INPUT, TokenKind.INPUT.getName())),
                List.of(new ProjectUsageResponse.ModelUsage("deepseek", "deepseek-v4-pro",
                        tokens)),
                List.of(new ProjectUsageResponse.RoleUsage("BA", "需求分析师", tokens)),
                List.of(new ProjectUsageResponse.IterationUsage("42", 1, tokens))));

        performAsUser(get("/api/projects/100/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value("100"))
                .andExpect(jsonPath("$.data.total.input").value(100))
                // 平台成本：币种分桶（键 = 币种码）
                .andExpect(jsonPath("$.data.cost.USD").value(0.003))
                // 未配价标注：档位 Integer code + 名称随附（#34 房规）
                .andExpect(jsonPath("$.data.unpriced[0].tokenKind").value(1))
                .andExpect(jsonPath("$.data.unpriced[0].tokenKindName").value("输入"))
                .andExpect(jsonPath("$.data.byModel[0].model").value("deepseek-v4-pro"))
                .andExpect(jsonPath("$.data.byRole[0].role").value("BA"))
                .andExpect(jsonPath("$.data.byRole[0].roleLabel").value("需求分析师"))
                // 按期聚合：期 id + 期序号
                .andExpect(jsonPath("$.data.byIteration[0].iterationId").value("42"))
                .andExpect(jsonPath("$.data.byIteration[0].seq").value(1));
    }

    @Test
    void given_workspace_when_source_package_then_binary_file_returned() throws Exception {
        byte[] bytes = {0x1f, (byte) 0x8b, 0x08, 0x00, 0x74, 0x61, 0x72};
        when(appService.sourcePackage(100L)).thenReturn(bytes);

        byte[] body = performAsUser(get("/api/projects/100/source-package"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/gzip"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).containsExactly(bytes); // 真实文件字节（不走 JSON 信封）
    }

    @Test
    void given_project_when_preview_then_url_returned() throws Exception {
        when(appService.preview(100L)).thenReturn(new ProjectPreviewResponse(
                "http://localhost:30080"));

        performAsUser(get("/api/projects/100/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("http://localhost:30080"));
    }

    @Test
    void given_prd_in_workspace_when_get_prd_then_content_and_updated_at_returned() throws Exception {
        when(queryAppService.prd(100L)).thenReturn(new PrdResponse("100",
                "# 官网 PRD\n\n目标：三页官网。\n", Instant.ofEpochSecond(1756100000)));

        performAsUser(get("/api/projects/100/prd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value("100"))
                .andExpect(jsonPath("$.data.content").value("# 官网 PRD\n\n目标：三页官网。\n"))
                // Instant → ISO-8601（Jackson2ObjectMapperBuilder 缺省关时间戳）
                .andExpect(jsonPath("$.data.updatedAt")
                        .value(Instant.ofEpochSecond(1756100000).toString()));
    }

    @Test
    void given_prd_not_produced_when_get_prd_then_prj_015_as_404() throws Exception {
        // 「未产出」口径：404 PRJ_015（区别于项目不存在的 PRJ_001）——前端区分「还没产出」
        when(queryAppService.prd(100L))
                .thenThrow(new ApplicationException(ProjectMessage.PRD_NOT_PRODUCED));

        performAsUser(get("/api/projects/100/prd"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("PRD 尚未产出"));
    }

    // ---------- 夹具 ----------

    /** 详情夹具（列表字段 + 主链定义 + 门就绪的最小可用形态）。 */
    private ProjectDetailResponse detailOf(String id, ProjectStatus status,
                                           String stage, Integer taskCount, boolean archived) {
        return new ProjectDetailResponse(id, "官网 demo", ProjectType.WEBSITE, "官网",
                "opencode", "900", stage, stage, status, status.getName(), taskCount,
                archived, LocalDateTime.of(2026, 8, 22, 10, 0), List.of(), null);
    }

    /**
     * MVC 切片不含 cartisan-web autoconfig，手动注册其全局异常处理器
     * （BaseEnum Jackson 序列化经类级 @Import 引入，对齐生产序列化行为）。
     */
    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }

    /**
     * query param 的 BaseEnum 按 code 绑定走 CartisanWebAutoConfiguration
     * 注册的 converter factory（切片不含该 autoconfig，此处对齐注册——
     * status=1 → ProjectStatusFilter.ACTIVE）。
     */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class EnumParamBindingConfig implements WebMvcConfigurer {

        @Override
        public void addFormatters(FormatterRegistry registry) {
            registry.addConverterFactory(new BaseEnumConverter());
        }
    }
}
