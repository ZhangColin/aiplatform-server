package com.aieducenter.aiplatform.base.agentengine.application.dto.command;

import jakarta.validation.constraints.NotNull;

/**
 * 权限审批命令（人做决策：agent 请求权限时由用户批准/拒绝）。
 */
public record AgentPermissionCommand(

        @NotNull(message = "approve 不能为空")
        Boolean approve) {
}
