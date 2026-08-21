package com.aieducenter.aiplatform.base.workspace.application.dto.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 工作区内执行命令的命令。
 */
public record WorkspaceExecCommand(

        @NotBlank(message = "command 不能为空")
        String command) {
}
