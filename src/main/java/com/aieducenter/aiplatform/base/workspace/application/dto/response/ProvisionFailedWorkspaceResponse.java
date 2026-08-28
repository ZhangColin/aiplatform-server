package com.aieducenter.aiplatform.base.workspace.application.dto.response;

import java.time.LocalDateTime;

/**
 * 置备失败工作区投影（#63 失败呈现）：workbench「待办可见」的查询面——failed 态工作区
 * 的 workspaceId（导航锚点）、失败原因（归一化错误码 + 文案）与失败时刻。
 */
public record ProvisionFailedWorkspaceResponse(
        String workspaceId,
        String provisionError,
        LocalDateTime failedAt) {
}
