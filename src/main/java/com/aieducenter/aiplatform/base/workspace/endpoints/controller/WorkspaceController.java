package com.aieducenter.aiplatform.base.workspace.endpoints.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 工作区与环境最小 REST 面（B0 蓝图 §2 片1b：创建/exec/销毁 + 查询，swagger 可见，
 * 供验收与调试）。正式消费面在 business.project（片5 总装：创建项目时编排工作区）。
 */
@RestController
@Validated
@RequestMapping("/api/workspaces")
@Tag(name = "工作区与环境", description = "环境生命周期：Docker 后端 + 中间件资源 + 落库接回（base.workspace）")
public class WorkspaceController {

    private final WorkspaceLifecycleAppService appService;

    public WorkspaceController(WorkspaceLifecycleAppService appService) {
        this.appService = appService;
    }

    @PostMapping
    @Operation(summary = "创建工作区", description = """
            记录落库（provisioning 态）即返回：dev 容器 + 专属 network + 独立 pg/redis 转后台
            置备（成功回填端口 + 资源转 ready，失败转 failed）。kind 缺省 1（DEV）；Phase A 仅
            支持 DEV，其余 kind 返回 WSP_007。资源 url 即 .env 注入的容器网络内连接串（含凭据），
            置备完成前清单为空、端口为 0。""")
    public ApiResponse<WorkspaceResponse> create(
            @RequestBody(required = false) CreateWorkspaceCommand command) {
        return ApiResponse.ok(
                appService.create(command == null ? new CreateWorkspaceCommand(null) : command));
    }

    @GetMapping("/{workspaceId}")
    @Operation(summary = "查询工作区", description = "记录 + 资源清单 + 置备状态/失败原因。服务重启后可查（重启接回的验证面）。")
    public ApiResponse<WorkspaceResponse> get(
            @Parameter(description = "工作区 id（TSID 字符串）") @PathVariable String workspaceId) {
        return ApiResponse.ok(appService.get(workspaceId));
    }

    @PostMapping("/{workspaceId}/retry")
    @Operation(summary = "重试置备", description = """
            置备失败（failed）转置备中（provisioning）并重新提交后台置备，成功回填端口 +
            资源转 ready；非 failed 态返回 WSP_009。""")
    public ApiResponse<WorkspaceResponse> retry(
            @Parameter(description = "工作区 id") @PathVariable String workspaceId) {
        return ApiResponse.ok(appService.retry(workspaceId));
    }

    @PostMapping("/{workspaceId}/exec")
    @Operation(summary = "工作区内执行命令", description = "dev 容器内 sh -c 执行，返回 stdout/stderr/exitCode；非 0 退出码是命令失败，不是环境故障。")
    public ApiResponse<ExecResultResponse> exec(
            @Parameter(description = "工作区 id") @PathVariable String workspaceId,
            @Valid @RequestBody WorkspaceExecCommand command) {
        return ApiResponse.ok(appService.exec(workspaceId, command));
    }

    @DeleteMapping("/{workspaceId}")
    @Operation(summary = "销毁工作区", description = "级联清理：容器（dev/pg/redis）→ network → 卷 → 库记录。")
    public ApiResponse<Void> destroy(
            @Parameter(description = "工作区 id") @PathVariable String workspaceId) {
        appService.destroy(workspaceId);
        return ApiResponse.ok();
    }
}
