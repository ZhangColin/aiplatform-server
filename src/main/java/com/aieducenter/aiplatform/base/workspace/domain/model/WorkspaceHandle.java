package com.aieducenter.aiplatform.base.workspace.domain.model;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;

/**
 * 一个工作区在环境后端处的运行时句柄（CONTEXT.md「环境」能力面的操作锚点）：
 * exec / exposePort / destroyWorkspace 都以它寻址。
 *
 * <p>与库记录同形（{@code wsp_workspaces} 持久化后可随时重建），服务重启接回
 * = 从记录还原本句柄。hostPort/previewPort 仅 dev 环境有意义（引擎接入点宿主
 * 端口 / 预览宿主端口），runtime 环境为 0。</p>
 */
public record WorkspaceHandle(
        WorkspaceId workspaceId,
        EnvKind kind,
        String containerName,
        String networkName,
        int hostPort,
        int previewPort) {

    public static WorkspaceHandle dev(WorkspaceId workspaceId, String containerName,
                                      String networkName, int hostPort, int previewPort) {
        return new WorkspaceHandle(workspaceId, EnvKind.DEV, containerName, networkName,
                hostPort, previewPort);
    }

    public static WorkspaceHandle runtime(WorkspaceId workspaceId, EnvKind kind,
                                          String containerName, String networkName) {
        return new WorkspaceHandle(workspaceId, kind, containerName, networkName, 0, 0);
    }
}
