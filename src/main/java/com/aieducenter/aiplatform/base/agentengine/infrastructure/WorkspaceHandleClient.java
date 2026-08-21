package com.aieducenter.aiplatform.base.agentengine.infrastructure;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;

/**
 * 工作区句柄取用（跨上下文调用的接口半边，编写规范示例 2 形态）：agentengine 对
 * workspace 的依赖收敛于此——按 workspaceId 取环境操作锚点 {@code WorkspaceHandle}。
 * 本地同进程直调 workspace 应用层；将来 base 拆服务时换 REST 适配器，调用方不动。
 */
public interface WorkspaceHandleClient {

    /**
     * 取工作区句柄；工作区不存在由 workspace 侧抛 WSP_001（404）。
     */
    WorkspaceHandle handleOf(String workspaceId);
}
