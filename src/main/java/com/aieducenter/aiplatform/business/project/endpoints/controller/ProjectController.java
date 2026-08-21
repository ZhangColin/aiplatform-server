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

import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.CreateProjectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectCreatedResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;

/**
 * 项目主链 REST 面（demo ProjectController 的重写，B0 §2 片5）：对话建项目
 * （选引擎，建即自动跑 BA）→ 下任务 / 答复等待点（ProjectAgentController）→
 * 删除真删级联。门操作（approve/reject）归片5b（票 #23）。
 */
@RestController
@RequestMapping("/api/projects")
@Validated
@Tag(name = "Projects", description = "项目主链：建项目 / 列表 / 详情 / 删除（门操作与需求池归后续版本）")
public class ProjectController {

    private final ProjectLifecycleAppService appService;

    public ProjectController(ProjectLifecycleAppService appService) {
        this.appService = appService;
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
