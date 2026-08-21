package com.aieducenter.aiplatform.base.workspace.application.event;

import java.time.Instant;
import java.util.UUID;

import com.cartisan.event.ApplicationEvent;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

/**
 * 工作区已创建（A1 §4 口子④）：环境后端副作用落定且 {@code wsp_*} 记录入库后，
 * 应用层在事务内经 PUBLISHER 端口发布（订阅方 AFTER_COMMIT 送达）。
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
