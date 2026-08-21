package com.aieducenter.aiplatform.base.eventhub.domain.model;

import java.time.Instant;
import java.util.Map;

import cn.hutool.core.map.MapUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 信封契约（SSE事件清单：data = {type, payload, ts}，payload 恒对象、内禁 type 键名）。
 */
class EventEnvelopeTest {

    private static final Instant TS = Instant.parse("2026-08-21T02:15:33.123Z");

    @Test
    void given_valid_type_payload_ts_when_create_then_fields_kept() {
        // Given
        Map<String, Object> payload = Map.of("projectId", "a1b2c3d4", "url", "http://localhost:30080");

        // When
        EventEnvelope envelope = new EventEnvelope("preview-ready", payload, TS);

        // Then
        assertThat(envelope.type()).isEqualTo("preview-ready");
        assertThat(envelope.payload())
                .containsEntry("projectId", "a1b2c3d4")
                .containsEntry("url", "http://localhost:30080");
        assertThat(envelope.ts()).isEqualTo(TS);
    }

    @Test
    void given_payload_with_type_key_when_create_then_rejected() {
        // payload 内禁用 type 键名（demo 三层嵌套信封的教训，ADR-0001）
        assertThatThrownBy(() -> new EventEnvelope("workspace-created",
                Map.of("type", "WEBSITE", "projectId", "a1b2c3d4"), TS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    void given_null_payload_when_create_then_rejected() {
        // payload 恒为对象（空对象合法，null 不合法）
        assertThatThrownBy(() -> new EventEnvelope("workspace-destroyed", null, TS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void given_blank_type_when_create_then_rejected() {
        assertThatThrownBy(() -> new EventEnvelope(" ", Map.of("projectId", "a1b2c3d4"), TS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    void given_null_ts_when_create_then_rejected() {
        assertThatThrownBy(() -> new EventEnvelope("workspace-destroyed", Map.of("projectId", "a1b2c3d4"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ts");
    }

    @Test
    void given_mutable_payload_when_create_then_snapshot_taken() {
        // 发布后调用方继续改原 map，不得影响已入信封的载荷
        Map<String, Object> payload = MapUtil.newHashMap();
        payload.put("projectId", "a1b2c3d4");

        EventEnvelope envelope = new EventEnvelope("stage-changed", payload, TS);

        payload.put("projectId", "tampered");
        assertThat(envelope.payload()).containsEntry("projectId", "a1b2c3d4");
    }
}
