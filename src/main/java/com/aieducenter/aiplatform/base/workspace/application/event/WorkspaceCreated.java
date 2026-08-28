package com.aieducenter.aiplatform.base.workspace.application.event;

import java.time.Instant;
import java.util.UUID;

import com.cartisan.event.ApplicationEvent;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

/**
 * 工作区已创建（A1 §4 口子④）：{@code wsp_*} 记录（PROVISIONING 态）入库后，
 * 应用层在事务内经 PUBLISHER 端口发布（订阅方 AFTER_COMMIT 送达）。#61 起语义 =
 * 记录就绪、容器后台置备中（docker 副作用转后台收敛，见 {@code WorkspaceProvisionAppService}）。
 * workspaceId 寻址、无 projectId——业务订阅方自行映射（ADR-0001：base 不发 SSE）。
 */
public record WorkspaceCreated(
        String eventId,
        Instant occurredAt,
        WorkspaceId workspaceId,
        EnvKind kind) implements ApplicationEvent {

    public static WorkspaceCreated of(WorkspaceId workspaceId, EnvKind kind) {
        return new WorkspaceCreated(UUID.randomUUID().toString(), Instant.now(), workspaceId, kind);
    }

    @Override
    public String eventType() {
        return "WorkspaceCreated";
    }
}
