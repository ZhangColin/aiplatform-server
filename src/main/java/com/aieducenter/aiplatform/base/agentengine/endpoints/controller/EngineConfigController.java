package com.aieducenter.aiplatform.base.agentengine.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.base.agentengine.application.EngineConfigAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.SwitchEngineCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.EngineConfigResponse;

/**
 * 引擎全局配置（票 #42）：后台查看 / 切换平台当前生效引擎——引擎选择从创建参数
 * 与用户界面移除（用户不懂引擎），平台用哪个引擎由服务端统一配置。前端简易后台页
 * （/admin，v1 仅引擎配置）的消费面；能力矩阵端点（/api/agent-engines）只读不动。
 */
@RestController
@RequestMapping("/api/admin/engine-config")
@Validated
@Tag(name = "后台配置", description = "平台后台配置（/admin 面，v1 仅引擎，base.agentengine）")
public class EngineConfigController {

    private final EngineConfigAppService appService;

    public EngineConfigController(EngineConfigAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "当前生效引擎（后台）", description = """
            平台当前生效的开发智能体引擎名。从未配置时返回注册表缺省 opencode，
            不报错。可切换的合法值全量见 `GET /api/agent-engines`（能力矩阵），
            本值即矩阵中的当前项。生效口径 = 新项目生效、存量不迁：切换只影响
            此后创建的项目，存量项目固化其创建时的引擎。""")
    public ApiResponse<EngineConfigResponse> current() {
        return ApiResponse.ok(appService.current());
    }

    @PutMapping
    @Operation(summary = "切换生效引擎（后台）", description = """
            engine 必填，须 ∈ `GET /api/agent-engines` 注册表，非法值 400 AGT_009。
            即时生效（读库不缓存），持久化重启不丢。只影响此后创建的项目——
            存量项目固化其创建时的引擎跑完，任务下发走项目记录，不受切换影响。""")
    public ApiResponse<EngineConfigResponse> switchEngine(
            @Valid @RequestBody SwitchEngineCommand command) {
        return ApiResponse.ok(appService.switchTo(command.engine()));
    }
}
