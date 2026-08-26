package com.aieducenter.aiplatform.base.agentengine.endpoints.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.base.agentengine.application.AgentEngineRegistry;

/**
 * 引擎能力矩阵（A1 §1.5 如实暴露）：任务下发的 engine 可选值与交互能力——
 * 界面与编排层据此隐藏不支持的入口（dsh 无问答无权限），不猜。
 */
@RestController
@RequestMapping("/api/agent-engines")
@Tag(name = "开发智能体", description = "引擎注册与能力矩阵（base.agentengine）")
public class AgentEnginesController {

    private final AgentEngineRegistry registry;

    public AgentEnginesController(AgentEngineRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    @Operation(summary = "引擎能力矩阵", description = """
            显式注册表全量（换引擎 = 实现端口 + 注册表登记，前端自动出现新选项）：
            name/label/questionSupported/permissionSupported/note。任务下发 engine 参数
            取 name；缺省 = 后台全局配置的生效引擎（`GET /api/admin/engine-config`，
            未配置时 opencode）。""")
    public ApiResponse<List<AgentEngineRegistry.EngineInfo>> matrix() {
        return ApiResponse.ok(registry.matrix());
    }
}
