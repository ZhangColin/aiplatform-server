package com.aieducenter.aiplatform.base.agentengine.application.dto.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 切换生效引擎命令（票 #42）：值须 ∈ 引擎注册表（/api/agent-engines），
 * 校验语义复用注册表，非法值 400 AGT_009。
 *
 * @param engine 生效引擎名（注册表键：opencode / dsh）
 */
public record SwitchEngineCommand(

        @NotBlank(message = "引擎名不能为空")
        String engine
) {
}
