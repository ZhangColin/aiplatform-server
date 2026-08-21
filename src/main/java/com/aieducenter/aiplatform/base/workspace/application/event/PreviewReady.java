package com.aieducenter.aiplatform.base.workspace.application.event;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import com.cartisan.event.ApplicationEvent;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

/**
 * 预览已就绪（A1 §4 口子④）：exposePort 拿到可访问 URL 后发布（订阅方 AFTER_COMMIT
 * 送达）。中性载荷，无 projectId——SSE 呈现（含 projectId）由业务编排层另行发射。
 */
public record PreviewReady(
        String eventId,
        Instant occurredAt,
        WorkspaceId workspaceId,
        URI url) implements ApplicationEvent {

    public static PreviewReady of(WorkspaceId workspaceId, URI url) {
        return new PreviewReady(UUID.randomUUID().toString(), Instant.now(), workspaceId, url);
    }

    @Override
    public String eventType() {
        return "PreviewReady";
    }
}
