package com.aieducenter.aiplatform.base.workspace.application.event;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生命周期三事件（A1 §4.1）：cartisan ApplicationEvent 契约（eventId/occurredAt/
 * eventType）+ 中性载荷透传（workspaceId 寻址，无 projectId）。
 */
class WorkspaceLifecycleEventTest {

    @Test
    void given_created_event_when_of_then_contract_and_payload_filled() {
        WorkspaceCreated event = WorkspaceCreated.of(WorkspaceId.of("42"), EnvKind.DEV);

        assertThat(event.eventType()).isEqualTo("WorkspaceCreated");
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.workspaceId().value()).isEqualTo("42");
        assertThat(event.kind()).isEqualTo(EnvKind.DEV);
    }

    @Test
    void given_created_event_when_occurred_twice_then_event_ids_distinct() {
        assertThat(WorkspaceCreated.of(WorkspaceId.of("42"), EnvKind.DEV).eventId())
                .isNotEqualTo(WorkspaceCreated.of(WorkspaceId.of("42"), EnvKind.DEV).eventId());
    }

    @Test
    void given_destroyed_event_when_of_then_contract_and_payload_filled() {
        WorkspaceDestroyed event = WorkspaceDestroyed.of(WorkspaceId.of("42"));

        assertThat(event.eventType()).isEqualTo("WorkspaceDestroyed");
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.workspaceId().value()).isEqualTo("42");
    }

    @Test
    void given_preview_ready_event_when_of_then_url_passed_through() {
        PreviewReady event = PreviewReady.of(WorkspaceId.of("42"), URI.create("http://localhost:30080/"));

        assertThat(event.eventType()).isEqualTo("PreviewReady");
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.workspaceId().value()).isEqualTo("42");
        assertThat(event.url()).isEqualTo(URI.create("http://localhost:30080/"));
    }
}
