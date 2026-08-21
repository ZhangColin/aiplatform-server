package com.aieducenter.aiplatform.base.workspace.application.event;

import java.time.Instant;
import java.util.UUID;

import com.cartisan.event.ApplicationEvent;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

/**
 * 工作区已销毁（A1 §4 口子④）：物理资源级联清理完毕且记录删除的删除事务内发布
 * （订阅方 AFTER_COMMIT 送达）。中性载荷，无 projectId。
 */
public record WorkspaceDestroyed(
        String eventId,
        Instant occurredAt,
        WorkspaceId workspaceId) implements ApplicationEvent {

    public static WorkspaceDestroyed of(WorkspaceId workspaceId) {
        return new WorkspaceDestroyed(UUID.randomUUID().toString(), Instant.now(), workspaceId);
    }

    @Override
    public String eventType() {
        return "WorkspaceDestroyed";
    }
}
