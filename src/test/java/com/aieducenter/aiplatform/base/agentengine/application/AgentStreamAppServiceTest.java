package com.aieducenter.aiplatform.base.agentengine.application;

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
 * agent 流通道语义（SSE事件清单·通道二）：必带关联字段 runId、streamId 取其值
 * （id = {runId}:{seq}，补发缝第一天留好）、?runId= / ?projectId= 过滤。
 * 用真内核 + 记录 sender 验证接线（通道语义归应用层，内核零业务概念）。
 */
class AgentStreamAppServiceTest {

    private final RecordingSseSender sender = new RecordingSseSender();
    private SseChannelHub hub;
    private AgentStreamAppService appService;

    @BeforeEach
    void setUp() {
        hub = new SseChannelHub(sender, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                Duration.ofSeconds(600));
        appService = new AgentStreamAppService(hub);
    }

    @AfterEach
    void tearDown() {
        hub.shutdown();
    }

    @Test
    void given_payload_without_run_id_when_publish_then_rejected() {
        assertThatThrownBy(() -> appService.publish("task-start", Map.of("prompt", "写个落地页")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    @Test
    void given_publish_when_subscribed_then_event_id_uses_run_id_as_stream_id() {
        SseEmitter subscriber = appService.subscribe(null, null, null);

        appService.publish("task-start", Map.of("runId", "run-9", "prompt", "写个落地页"));
        appService.publish("task-finish", Map.of("runId", "run-9", "finish", "end"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("run-9:1", "run-9:2");
    }

    @Test
    void given_run_filter_when_publish_other_run_then_filtered_out() {
        // 任务进度页「看某个运行才挂」：?runId= 过滤（与 payload 关联字段同名）
        SseEmitter subscriber = appService.subscribe(null, "run-1", null);

        appService.publish("task-start", Map.of("runId", "run-2", "prompt", "x"));
        appService.publish("task-start", Map.of("runId", "run-1", "prompt", "y"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:1");
    }

    @Test
    void given_workspace_filter_when_publish_other_workspace_then_filtered_out() {
        // 片2a 底座任务端点直发事件以 workspaceId 关联——订阅过滤同名
        SseEmitter subscriber = appService.subscribe(null, null, "42");

        appService.publish("task-start", Map.of("runId", "run-1", "workspaceId", "43"));
        appService.publish("task-start", Map.of("runId", "run-2", "workspaceId", "42"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("run-2:1");
    }

    @Test
    void given_project_and_run_filters_when_publish_then_both_must_match() {
        // projectId 是片5 业务桥接注入的透传字段——过滤位先留（AND 语义）
        SseEmitter subscriber = appService.subscribe("proj-1", "run-1", null);

        appService.publish("task-start", Map.of("runId", "run-1", "prompt", "x"));
        appService.publish("task-start",
                Map.of("runId", "run-1", "projectId", "proj-1", "prompt", "y"));

        assertThat(sender.eventFramesOf(subscriber)).hasSize(1);
        assertThat(sender.eventFramesOf(subscriber).get(0).id()).isEqualTo("run-1:2");
    }
}
