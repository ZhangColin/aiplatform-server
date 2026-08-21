package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.project.application.ProjectGateAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.CreateProjectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.command.StageRejectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectCreatedResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectPreviewResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;

/**
 * 项目主链 REST 面（demo ProjectController 的重写，B0 §2 片5）：对话建项目
 * （选引擎，建即自动跑 BA）→ 下任务 / 答复等待点（ProjectAgentController）→
 * 门操作与收口（approve/reject，A3 §3/§5）→ 预览 → 删除真删级联。
 */
@RestController
@RequestMapping("/api/projects")
@Validated
@Tag(name = "Projects", description = "项目主链：建项目 / 列表 / 详情 / 门操作 / 预览 / 删除（需求池与归档归片5c）")
public class ProjectController {

    private final ProjectLifecycleAppService appService;
    private final ProjectGateAppService gateAppService;

    public ProjectController(ProjectLifecycleAppService appService,
                             ProjectGateAppService gateAppService) {
        this.appService = appService;
        this.gateAppService = gateAppService;
    }

    @PostMapping
    @Operation(summary = "建项目（对话建项目：建即自动跑 BA 需求梳理）",
            description = "选引擎（缺省 opencode）→ dev 工作区 + 专属 pg/redis 就绪 → 第 1 期（BA 段 OPEN）。"
                    + "响应携带自动 BA 运行 runId（挂 /api/agent-events?runId= 的锚）。"
                    + "SSE：workspace-created → stage-changed(BA) → agent 流事件")
    public ApiResponse<ProjectCreatedResponse> create(@Valid @RequestBody CreateProjectCommand command) {
        return ApiResponse.ok(appService.create(command));
    }

    @GetMapping
    @Operation(summary = "项目列表", description = "创建时间倒序；含期位置与派生状态（开发中/已交付）")
    public ApiResponse<List<ProjectResponse>> list() {
        return ApiResponse.ok(appService.list());
    }

    @GetMapping("/{id}")
    @Operation(summary = "项目详情", description = "期位置（阶段/标签/计数）+ 派生项目状态；主链定义数据与门就绪归项目详情扩展（票 #24）")
    public ApiResponse<ProjectResponse> get(@PathVariable String id) {
        return ApiResponse.ok(appService.get(parseId(id)));
    }

    @PostMapping("/{id}/stage/approve")
    @Operation(summary = "门通过（推进；验收门通过即收口）",
            description = "无体——拍板即全部事实（approve 也留痕 prj_confirmations，含 accountId）。"
                    + "门禁 = 引擎计数（每门 taskCount≥1，验收门=0）∧ 业务谓词（开发完成确认 = "
                    + "无未关闭 Bug），不足 409 PRJ_007/PRJ_008；无门段 409 PRJ_009；无 OPEN 期 409 PRJ_010。"
                    + "需求确认（G1）通过自动跑 Demo；验收（G4）通过即收口：期 CLOSED、项目已交付。"
                    + "SSE：stage-changed(approved=true)")
    public ApiResponse<ProjectResponse> approve(@PathVariable String id) {
        return ApiResponse.ok(gateAppService.approve(parseId(id)));
    }

    @PostMapping("/{id}/stage/reject")
    @Operation(summary = "门驳回（一律停留当前阶段）",
            description = "reason 必填（驳回反馈是前端展示与纪要来源）。驳回不迁移阶段——"
                    + "验收驳回停留验收段，开发平台照常下修复任务，用户再验收（A3 §3）；"
                    + "留痕落 prj_confirmations（decision=驳回）。SSE：stage-changed(rejected=true, reason)")
    public ApiResponse<ProjectResponse> reject(@PathVariable String id,
                                               @Valid @RequestBody StageRejectCommand command) {
        return ApiResponse.ok(gateAppService.reject(parseId(id), command.reason()));
    }

    @GetMapping("/{id}/preview")
    @Operation(summary = "预览（工作区端口暴露）",
            description = "把工作区容器端口发布到主机返回可访问 URL（localhost）；端口真实暴露后"
                    + "SSE preview-ready。Demo 段产物可访问即预期效果（未起服务时连接拒绝属真实状态）")
    public ApiResponse<ProjectPreviewResponse> preview(@PathVariable String id) {
        return ApiResponse.ok(appService.preview(parseId(id)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除项目（真删级联）",
            description = "容器/网络/卷级联清理 + wsp_*/prj_* 库记录删除；SSE workspace-destroyed")
    public ApiResponse<Void> delete(@PathVariable String id) {
        appService.delete(parseId(id));
        return ApiResponse.ok();
    }

    /** 寻址解析收口（{@link ProjectIds}，两 controller 共用）。 */
    private Long parseId(String id) {
        return ProjectIds.parse(id);
    }
}
