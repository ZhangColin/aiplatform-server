package com.aieducenter.aiplatform.base.workspace.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;

/**
 * 工作区响应（记录形态 + 资源清单 + .env 注入的连接串原文）。
 * 枚举字段按编写规范以 Integer code 序列化，xxxName 补显示名。
 * {@code status} 暴露置备状态（#58/#61）：创建返回时记录已就绪、容器后台置备中
 * （PROVISIONING、端口 0、资源清单空），置备完成后回填端口 + 资源转 READY。
 * {@code provisionError} 暴露置备失败原因（#63）：failed 态为归一化错误码 + 文案，
 * 其余态为 null——工作台可见、需要环境时阻塞的失败依据（状态以查询为准，ADR-0001）。
 */
public record WorkspaceResponse(
        String workspaceId,
        EnvKind kind,
        String kindName,
        String containerName,
        String networkName,
        int hostPort,
        int previewPort,
        ProvisioningStatus status,
        String statusName,
        String provisionError,
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
