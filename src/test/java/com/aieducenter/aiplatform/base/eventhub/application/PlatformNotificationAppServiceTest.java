package com.aieducenter.aiplatform.base.eventhub.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.RecordingSseSender;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseChannelHub;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseServerEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通知通道语义（SSE事件清单·通道一）：关联字段 projectId——payload 必带、订阅过滤同名、
 * streamId 取其值。用真内核 + 记录 sender 验证接线（通道语义归应用层，内核零业务概念）。
 */
class PlatformNotificationAppServiceTest {

    private final RecordingSseSender sender = new RecordingSseSender();
    private SseChannelHub hub;
    private PlatformNotificationAppService appService;

    @BeforeEach
    void setUp() {
        hub = new SseChannelHub(sender, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                Duration.ofSeconds(600));
        appService = new PlatformNotificationAppService(hub);
    }

    @AfterEach
    void tearDown() {
        hub.shutdown();
    }

    @Test
    void given_payload_without_project_id_when_publish_then_rejected() {
        // 通知通道契约：payload 必带关联字段 projectId（SSE事件清单·信封）
        assertThatThrownBy(() -> appService.publish("preview-ready", Map.of("url", "http://localhost:30080")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
    }

    @Test
    void given_blank_project_id_when_publish_then_rejected() {
        assertThatThrownBy(() -> appService.publish("preview-ready",
                Map.of("projectId", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
    }

    @Test
    void given_publish_when_subscribed_then_event_id_uses_project_id_as_stream_id() {
        // 通知通道 streamId=projectId（id = {projectId}:{seq}，补发缝第一天留好）
        SseEmitter subscriber = appService.subscribe(null);

        appService.publish("workspace-created",
                Map.of("projectId", "a1b2c3d4", "projectName", "官网 demo"));
        appService.publish("stage-changed", Map.of("projectId", "a1b2c3d4", "stage", "DEV"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("a1b2c3d4:1", "a1b2c3d4:2");
    }

    @Test
    void given_subscribe_without_project_id_when_publish_any_project_then_all_received() {
        // 缺省全量（ADR-0001 寻址：开发平台视角）
        SseEmitter subscriber = appService.subscribe(null);

        appService.publish("stage-changed", Map.of("projectId", "p1", "stage", "BA"));
        appService.publish("stage-changed", Map.of("projectId", "p2", "stage", "DEV"));

        assertThat(sender.eventFramesOf(subscriber)).hasSize(2);
    }

    @Test
    void given_subscribe_with_project_id_when_publish_other_project_then_filtered_out() {
        SseEmitter subscriber = appService.subscribe("p1");

        appService.publish("stage-changed", Map.of("projectId", "p2", "stage", "DEV"));
        appService.publish("stage-changed", Map.of("projectId", "p1", "stage", "BA"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("p1:1"); // p2 事件被过滤，p1 序列不受影响
    }
}
