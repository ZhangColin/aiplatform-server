package com.aieducenter.aiplatform.base.workspace.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;

/**
 * 工作区响应（记录形态 + 资源清单 + .env 注入的连接串原文）。
 * 枚举字段按编写规范以 Integer code 序列化，xxxName 补显示名。
 */
public record WorkspaceResponse(
        String workspaceId,
        EnvKind kind,
        String kindName,
        String containerName,
        String networkName,
        int hostPort,
        int previewPort,
        List<MiddlewareResourceResponse> resources,
        LocalDateTime createdAt) {

    /**
     * 中间件资源响应（url = 容器网络内连接串，即 /workspace/.env 注入原文）。
     */
    public record MiddlewareResourceResponse(
            MiddlewareKind kind,
            String kindName,
            String containerName,
            int hostPort,
            String url) {
    }
}
