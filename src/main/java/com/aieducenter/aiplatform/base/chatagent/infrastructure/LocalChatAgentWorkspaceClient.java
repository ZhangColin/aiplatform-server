package com.aieducenter.aiplatform.base.chatagent.infrastructure;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;

/**
 * 进程内适配：直调 workspace 应用层 {@code handleOf}（跨上下文经应用层，编写规范）。
 */
@Component
public class LocalChatAgentWorkspaceClient implements ChatAgentWorkspaceClient {

    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public LocalChatAgentWorkspaceClient(WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    @Override
    public WorkspaceHandle handleOf(String workspaceId) {
        return workspaceLifecycleAppService.handleOf(workspaceId);
    }
}
