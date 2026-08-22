package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.util.List;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.project.application.ProjectDemandPoolAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectGateAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.AddDemandEntryCommand;
import com.aieducenter.aiplatform.business.project.application.dto.command.CreateProjectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.command.StageRejectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.DemandPoolEntryResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectCreatedResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectPreviewResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectUsageResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatusFilter;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * 项目主链 REST 面（demo ProjectController 的重写，B0 §2 片5）：对话建项目
 * （选引擎，建即自动跑 BA）→ 下任务 / 答复等待点（ProjectAgentController）→
 * 门操作与收口（approve/reject，A3 §3/§5）→ 需求池 / 归档 / 详情 / 用量 /
 * 源码包下载（片5c 项目周边，票 #24）→ 预览 → 删除真删级联。
 */
@RestController
@RequestMapping("/api/projects")
@Validated
@Tag(name = "Projects", description = "项目主链：建项目 / 列表 / 详情 / 门操作 / 需求池 / 归档 / 用量 / 源码包 / 预览 / 删除")
public class ProjectController {

    private final ProjectLifecycleAppService appService;
    private final ProjectGateAppService gateAppService;
    private final ProjectQueryAppService queryAppService;
    private final ProjectDemandPoolAppService demandPoolAppService;

    public ProjectController(ProjectLifecycleAppService appService,
                             ProjectGateAppService gateAppService,
                             ProjectQueryAppService queryAppService,
                             ProjectDemandPoolAppService demandPoolAppService) {
        this.appService = appService;
        this.gateAppService = gateAppService;
        this.queryAppService = queryAppService;
        this.demandPoolAppService = demandPoolAppService;
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
    @Operation(summary = "项目列表（状态过滤）",
            description = "创建时间倒序。status 过滤（Integer code）：1=ACTIVE（开发中）/ 2=PENDING"
                    + "（存在 dev 待办：门就绪或等待点待处理）/ 3=ARCHIVED（已归档）；缺省 all。"
                    + "不合法取值 400 PRJ_014")
    public ApiResponse<List<ProjectResponse>> list(
            @RequestParam(required = false) ProjectStatusFilter status) {
        return ApiResponse.ok(queryAppService.list(status));
    }

    /**
     * status 绑定失败的兜底（#34）：非法 code/非数值在本层就是 400，映射回
     * PRJ_014 保持既有错误口径（本 controller 唯一可绑定枚举参数是 status，
     * 兜底不越界）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleStatusMismatch() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ProjectMessage.PROJECT_FILTER_UNKNOWN));
    }

    @GetMapping("/{id}")
    @Operation(summary = "项目详情（期位置 + 主链定义数据 + 门就绪 + 派生状态）",
            description = "前端渲染进度条与点亮按钮的全部数据面（A3 §5）：stages = 阶段序列"
                    + "（主链定义数据，过程演化 UI 少改）；gate = {actor, ready}（计数门禁 ∧"
                    + "业务谓词，无门段/已收口为 null）；status = 派生项目状态（Integer code："
                    + "1=开发中 2=已交付 3=已归档，有无 OPEN 期的投影，归档优先）")
    public ApiResponse<ProjectDetailResponse> get(@PathVariable String id) {
        return ApiResponse.ok(queryAppService.detail(parseId(id)));
    }

    @PostMapping("/{id}/stage/approve")
    @Operation(summary = "门通过（推进；验收门通过即收口）",
            description = "无体——拍板即全部事实（approve 也留痕 prj_confirmations，含 accountId）。"
                    + "门禁 = 引擎计数（每门 taskCount≥1，验收门=0）∧ 业务谓词（开发完成确认 = "
                    + "无未关闭 Bug），不足 409 PRJ_007/PRJ_008；无门段 409 PRJ_009；无 OPEN 期 409 PRJ_010。"
                    + "需求确认（G1）通过自动跑 Demo；验收（G4）通过即收口：期 CLOSED、项目已交付。"
                    + "SSE：stage-changed(approved=true)")
    public ApiResponse<ProjectDetailResponse> approve(@PathVariable String id) {
        return ApiResponse.ok(gateAppService.approve(parseId(id)));
    }

    @PostMapping("/{id}/stage/reject")
    @Operation(summary = "门驳回（一律停留当前阶段）",
            description = "reason 必填（驳回反馈是前端展示与纪要来源）。驳回不迁移阶段——"
                    + "验收驳回停留验收段，开发平台照常下修复任务，用户再验收（A3 §3）；"
                    + "留痕落 prj_confirmations（decision=驳回）。SSE：stage-changed(rejected=true, reason)")
    public ApiResponse<ProjectDetailResponse> reject(@PathVariable String id,
                                                     @Valid @RequestBody StageRejectCommand command) {
        return ApiResponse.ok(gateAppService.reject(parseId(id), command.reason()));
    }

    @PostMapping("/{id}/demand-pool")
    @Operation(summary = "需求池入池（随时可记）",
            description = "项目级收件清单（A3 §4）：验收前后、期开期关都能记；kind 可空"
                    + "（收件时不强分类），source 缺省用户；入池是显式动作（驳回反馈不自动入池）。"
                    + "开新期时作为需求梳理输入")
    public ApiResponse<DemandPoolEntryResponse> addDemandEntry(
            @PathVariable String id, @Valid @RequestBody AddDemandEntryCommand command) {
        return ApiResponse.ok(demandPoolAppService.add(parseId(id), command));
    }

    @GetMapping("/{id}/demand-pool")
    @Operation(summary = "需求池清单（新→旧）", description = "项目的收件清单时间线，记录时间倒序")
    public ApiResponse<List<DemandPoolEntryResponse>> demandEntries(@PathVariable String id) {
        return ApiResponse.ok(demandPoolAppService.entries(parseId(id)));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "归档（单向终点）",
            description = "落 archived_at（A3 §4）：区别于开发中/已交付的派生投影——归档是真实动作，"
                    + "重复归档 409 PRJ_013。归档不迁移期、不清工作区（工具项目级常开）")
    public ApiResponse<ProjectDetailResponse> archive(@PathVariable String id) {
        return ApiResponse.ok(appService.archive(parseId(id)));
    }

    @GetMapping("/{id}/usage")
    @Operation(summary = "项目用量（总量 + 平台成本 + 分模型 + 分角色 + 按期）",
            description = "经计量查询端口按 subject=projectId 聚合（A1 §2.5 + A6 §3）。"
                    + "cost 为平台成本口径（token × 事件时点生效单价的机械乘法，币种分桶不折算，"
                    + "无加价/售价）；unpriced 标注有用量但未配单价的档位（其分量不含于 cost，不伪装 0）；"
                    + "byIteration 按 dims.iterationId 聚合（run 发起时快照）——期后修复 run 不带该维度，"
                    + "入项目总量不入任何期桶（收口期成本定格）")
    public ApiResponse<ProjectUsageResponse> usage(@PathVariable String id) {
        return ApiResponse.ok(queryAppService.usage(parseId(id)));
    }

    @GetMapping("/{id}/source-package")
    @Operation(summary = "源码包下载（常开）",
            description = "交付物 = 源码包 + 仓内文档（A3 §2.2）：打包项目 dev 工作区为 tar.gz"
                    + "（排除 .env 机密与 node_modules）。响应为二进制文件流"
                    + "（application/gzip，本端点不走 ApiResponse JSON 信封）")
    public ResponseEntity<ByteArrayResource> sourcePackage(@PathVariable String id) {
        Long projectId = parseId(id);
        byte[] bytes = appService.sourcePackage(projectId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/gzip"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(projectId + "-source.tar.gz").build());
        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(bytes));
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
