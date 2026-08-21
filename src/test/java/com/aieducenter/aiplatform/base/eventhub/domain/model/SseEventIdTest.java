package com.aieducenter.aiplatform.base.eventhub.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSE 事件 id 契约（ADR-0001：id = {streamId}:{seq}——通知通道 streamId=projectId、
 * agent 流通道 streamId=runId；补发缝第一天留好，格式即契约）。
 */
class SseEventIdTest {

    @Test
    void given_streamId_and_seq_when_value_then_composite_format() {
        assertThat(new SseEventId("a1b2c3d4", 1L).value()).isEqualTo("a1b2c3d4:1");
        assertThat(new SseEventId("run-42", 17L).value()).isEqualTo("run-42:17");
    }

    @Test
    void given_blank_streamId_when_create_then_rejected() {
        assertThatThrownBy(() -> new SseEventId("", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streamId");
    }

    @Test
    void given_non_positive_seq_when_create_then_rejected() {
        assertThatThrownBy(() -> new SseEventId("a1b2c3d4", 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seq");
        assertThatThrownBy(() -> new SseEventId("a1b2c3d4", -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seq");
    }
}
