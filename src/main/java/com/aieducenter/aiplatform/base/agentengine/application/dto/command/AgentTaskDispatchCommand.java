package com.aieducenter.aiplatform.base.agentengine.application.dto.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 任务下发命令（底座任务端点）：systemPrompt / modelId 是入参（适配层零角色概念，
 * 角色卡编排归 business.project 片5）；sessionId 非空 = 复用既有会话续跑（A1 §1.2
 * 续跑缝）；engine 缺省 = 后台全局配置的生效引擎（票 #42，未配置时 opencode）。
 */
public record AgentTaskDispatchCommand(

        @NotBlank(message = "任务提示词不能为空")
        String prompt,

        String systemPrompt,

        String modelId,

        String engine,

        String sessionId) {
}
