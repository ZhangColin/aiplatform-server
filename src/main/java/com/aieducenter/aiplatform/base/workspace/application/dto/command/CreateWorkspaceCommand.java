package com.aieducenter.aiplatform.base.workspace.application.dto.command;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;

/**
 * 创建工作区命令。kind 缺省 DEV（Phase A 一个项目 = 一个 dev 环境，CONTEXT.md「项目」）。
 */
public record CreateWorkspaceCommand(EnvKind kind) {

    public EnvKind kindOrDefault() {
        return kind == null ? EnvKind.DEV : kind;
    }
}
