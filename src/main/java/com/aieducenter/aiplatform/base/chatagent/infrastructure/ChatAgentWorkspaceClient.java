package com.aieducenter.aiplatform.base.chatagent.infrastructure;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;

/**
 * 工作区句柄取用（#45 工作区桥，照 base.agentengine 的 WorkspaceHandleClient 形态）：
 * chatagent 对 workspace 的依赖收敛于此——按 workspaceId 取环境操作锚点，供
 * HarnessAgent 的项目 dev 工作区解析（容器即文件面）。本地同进程直调 workspace
 * 应用层；将来 base 拆服务时换 REST 适配器，调用方不动。
 */
public interface ChatAgentWorkspaceClient {

    /**
     * 取工作区句柄；工作区不存在由 workspace 侧抛 WSP_001（404）。
     */
    WorkspaceHandle handleOf(String workspaceId);
}
