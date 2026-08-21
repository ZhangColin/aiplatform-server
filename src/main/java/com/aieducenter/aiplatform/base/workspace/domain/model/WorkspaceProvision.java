package com.aieducenter.aiplatform.base.workspace.domain.model;

import java.util.List;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;

/**
 * createWorkspace 的产出：运行时句柄 + 随环境供给落定的中间件资源清单
 * （runtime 环境无资源，清单为空）。副作用已真实落定是返回前提。
 */
public record WorkspaceProvision(
        WorkspaceHandle handle,
        List<ProvisionedResource> resources) {

    public WorkspaceProvision {
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    public static WorkspaceProvision of(WorkspaceHandle handle, ProvisionedResource... resources) {
        return new WorkspaceProvision(handle, List.of(resources));
    }

    public WorkspaceId workspaceId() {
        return handle.workspaceId();
    }

    public EnvKind kind() {
        return handle.kind();
    }
}
