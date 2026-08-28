package com.aieducenter.aiplatform.base.agentengine.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.eventhub.domain.model.EventEnvelope;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.RecordingSseSender;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseChannelHub;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseServerEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * agent 流通道语义（SSE事件清单·通道二）：必带关联字段 runId、streamId 取其值
 * （id = {runId}:{seq}，补发缝第一天留好）、?runId= / ?projectId= 过滤、近期帧
 * 重放接线（#56：注册缓冲 + 新连/重连分野 + 容量配置）。
 * 用真内核 + 记录 sender 验证接线（通道语义归应用层，内核零业务概念）。
 */
class AgentStreamAppServiceTest {

    private final RecordingSseSender sender = new RecordingSseSender();
    private final List<SseChannelHub> hubs = new ArrayList<>();
    private AgentStreamAppService appService;

    @BeforeEach
    void setUp() {
        appService = newService(new AgentStreamProperties());
    }

    @AfterEach
    void tearDown() {
        hubs.forEach(SseChannelHub::shutdown);
    }

    private AgentStreamAppService newService(AgentStreamProperties properties) {
        SseChannelHub hub = new SseChannelHub(sender, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                Duration.ofSeconds(600));
        hubs.add(hub);
        return new AgentStreamAppService(hub, properties);
    }

    @Test
    void given_payload_without_run_id_when_publish_then_rejected() {
        assertThatThrownBy(() -> appService.publish("task-start", Map.of("prompt", "写个落地页")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    @Test
    void given_publish_when_subscribed_then_event_id_uses_run_id_as_stream_id() {
        SseEmitter subscriber = appService.subscribe(null, null, null, false);

        appService.publish("task-start", Map.of("runId", "run-9", "prompt", "写个落地页"));
        appService.publish("task-finish", Map.of("runId", "run-9", "finish", "end"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("run-9:1", "run-9:2");
    }

    @Test
    void given_run_filter_when_publish_other_run_then_filtered_out() {
        // 任务进度页「看某个运行才挂」：?runId= 过滤（与 payload 关联字段同名）
        SseEmitter subscriber = appService.subscribe(null, "run-1", null, false);

        appService.publish("task-start", Map.of("runId", "run-2", "prompt", "x"));
        appService.publish("task-start", Map.of("runId", "run-1", "prompt", "y"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:1");
    }

    @Test
    void given_workspace_filter_when_publish_other_workspace_then_filtered_out() {
        // 片2a 底座任务端点直发事件以 workspaceId 关联——订阅过滤同名
        SseEmitter subscriber = appService.subscribe(null, null, "42", false);

        appService.publish("task-start", Map.of("runId", "run-1", "workspaceId", "43"));
        appService.publish("task-start", Map.of("runId", "run-2", "workspaceId", "42"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("run-2:1");
    }

    @Test
    void given_project_and_run_filters_when_publish_then_both_must_match() {
        // projectId 是片5 业务桥接注入的透传字段——过滤位先留（AND 语义）
        SseEmitter subscriber = appService.subscribe("proj-1", "run-1", null, false);

        appService.publish("task-start", Map.of("runId", "run-1", "prompt", "x"));
        appService.publish("task-start",
                Map.of("runId", "run-1", "projectId", "proj-1", "prompt", "y"));

        assertThat(sender.eventFramesOf(subscriber)).hasSize(1);
        assertThat(sender.eventFramesOf(subscriber).get(0).id()).isEqualTo("run-1:2");
    }

    /** 规格值断言：默认重放深度 1000（#53 spec 定值；容量上界即内存上界，防无意改动）。 */
    @Test
    void given_default_properties_when_get_replay_depth_then_1000() {
        assertThat(new AgentStreamProperties().getReplayDepth()).isEqualTo(1000);
    }

    /**
     * 配置键真绑定（app.agent-stream.replay-depth）：走 Spring Boot Binder 实绑——
     * 前缀/字段名拼写错时字段静默吃默认值，POJO setter 测不出来（#56 AC）。
     */
    @Test
    void given_config_key_when_bind_then_replay_depth_wired() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
                Map.of("app.agent-stream.replay-depth", "3"));

        AgentStreamProperties bound = new Binder(source)
                .bind("app.agent-stream", Bindable.ofInstance(new AgentStreamProperties()))
                .get();

        assertThat(bound.getReplayDepth()).isEqualTo(3);
    }

    /**
     * 事故回归（#53 真机时序，#52 同路径）：建项目后 BA 起跑即死——error 帧（带
     * projectId 关联）在零订阅时发出，彼时浏览器还在导航/首编译；工作台就绪后按
     * projectId 建立订阅，帧必须到达（重放非重发，id 即原事件 id）。重放同样过
     * 订阅谓词——别的项目的帧不泄漏。
     */
    @Test
    void given_error_frame_at_zero_subscribers_when_delayed_project_subscribe_then_frame_arrives() {
        appService.publish(AgentEventTypes.ERROR, Map.of(
                AgentStreamAppService.PROJECT_FIELD, "7",
                AgentStreamAppService.RUN_FIELD, "run-9",
                "message", "Failed to create model: DEEPSEEK_API_KEY is required"));
        appService.publish(AgentEventTypes.ERROR, Map.of(
                AgentStreamAppService.PROJECT_FIELD, "8",
                AgentStreamAppService.RUN_FIELD, "run-10",
                "message", "别的项目的帧"));

        SseEmitter workbench = appService.subscribe("7", null, null, true);

        assertThat(sender.eventFramesOf(workbench)).hasSize(1);
        SseServerEvent frame = sender.eventFramesOf(workbench).get(0);
        assertThat(frame.id()).isEqualTo("run-9:1");   // 重放帧与实时帧同一 id 口径
        EventEnvelope envelope = (EventEnvelope) frame.data();
        assertThat(envelope.type()).isEqualTo(AgentEventTypes.ERROR);
        assertThat(envelope.payload())
                .containsEntry("projectId", "7")
                .containsEntry("runId", "run-9");
    }

    /** 重连分野的通道层对应：replay 关（带 Last-Event-ID 的重连）不收缓冲帧。 */
    @Test
    void given_buffered_frames_when_subscribe_without_replay_then_no_backlog() {
        appService.publish("task-start", Map.of("runId", "run-1", "prompt", "x"));

        SseEmitter reconnecting = appService.subscribe(null, "run-1", null, false);

        assertThat(sender.eventFramesOf(reconnecting)).isEmpty();
    }

    /** 容量可配（app.agent-stream.replay-depth）：depth=2 → 3 帧只重放最近 2 帧。 */
    @Test
    void given_replay_depth_2_when_publish_3_frames_then_only_last_2_replayed() {
        AgentStreamProperties properties = new AgentStreamProperties();
        properties.setReplayDepth(2);
        AgentStreamAppService shallow = newService(properties);
        for (int i = 1; i <= 3; i++) {
            shallow.publish("text", Map.of("runId", "run-1", "data", Map.of("delta", "块" + i)));
        }

        SseEmitter late = shallow.subscribe(null, null, null, true);

        assertThat(sender.eventFramesOf(late))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:2", "run-1:3");
    }
}
