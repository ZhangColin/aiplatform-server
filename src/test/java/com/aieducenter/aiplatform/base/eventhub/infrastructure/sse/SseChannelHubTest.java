package com.aieducenter.aiplatform.base.eventhub.infrastructure.sse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aieducenter.aiplatform.base.eventhub.domain.model.EventEnvelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSE 传输内核（ADR-0001：emitter 管理 / 心跳 / 过滤订阅 / 信封与 id 分配 /
 * fire-and-forget）。通道按名泛化、零业务概念；SseSender 缝注入做确定性验证。
 */
class SseChannelHubTest {

    private static final String NOTIFICATION = "platform-notification";
    private static final String AGENT_STREAM = "agent-stream";
    private static final Instant FIXED_TS = Instant.parse("2026-08-21T02:15:33.123Z");

    private final RecordingSseSender sender = new RecordingSseSender();
    private final List<SseChannelHub> hubs = new ArrayList<>();

    @AfterEach
    void tearDown() {
        hubs.forEach(SseChannelHub::shutdown);
    }

    private SseChannelHub newHub(Duration heartbeatInterval) {
        SseChannelHub hub = new SseChannelHub(sender,
                Clock.fixed(FIXED_TS, ZoneOffset.UTC), heartbeatInterval);
        hubs.add(hub);
        return hub;
    }

    @Test
    void given_default_configuration_when_check_heartbeat_interval_then_15s() {
        // 规格值（ADR-0001：每 15s 发 :ping 防代理掐空闲连接）——周期行为由短间隔测，
        // 但 15s 这个契约值本身也要有断言守住，防无意改动
        assertThat(SseChannelHub.DEFAULT_HEARTBEAT_INTERVAL).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void given_subscribe_when_connected_then_initial_ping_sent_immediately() {
        SseChannelHub hub = newHub(Duration.ofSeconds(600));

        SseEmitter emitter = hub.subscribe(NOTIFICATION, payload -> true);

        assertThat(sender.framesOf(emitter)).hasSize(1);
        SseServerEvent ping = sender.framesOf(emitter).get(0);
        assertThat(ping.comment()).isEqualTo("ping");
        assertThat(ping.id()).isNull();
        assertThat(ping.name()).isNull();
        assertThat(ping.data()).isNull();
    }

    @Test
    void given_matching_subscriber_when_broadcast_then_event_frame_with_envelope_and_id() {
        SseChannelHub hub = newHub(Duration.ofSeconds(600));
        SseEmitter emitter = hub.subscribe(NOTIFICATION, payload -> true);
        sender.framesOf(emitter).clear();

        hub.broadcast(NOTIFICATION, "a1b2c3d4", "workspace-created",
                Map.of("projectId", "a1b2c3d4", "projectName", "官网 demo"));

        assertThat(sender.framesOf(emitter)).hasSize(1);
        SseServerEvent frame = sender.framesOf(emitter).get(0);
        assertThat(frame.id()).isEqualTo("a1b2c3d4:1");
        assertThat(frame.name()).isEqualTo("event");
        assertThat(frame.data()).isInstanceOf(EventEnvelope.class);
        EventEnvelope envelope = (EventEnvelope) frame.data();
        assertThat(envelope.type()).isEqualTo("workspace-created");
        assertThat(envelope.payload()).containsEntry("projectId", "a1b2c3d4");
        assertThat(envelope.ts()).isEqualTo(FIXED_TS);
    }

    @Test
    void given_subscriber_filtered_out_when_broadcast_then_not_sent() {
        SseChannelHub hub = newHub(Duration.ofSeconds(600));
        SseEmitter subscriber = hub.subscribe(NOTIFICATION,
                payload -> "p1".equals(payload.get("projectId")));

        hub.broadcast(NOTIFICATION, "p2", "stage-changed",
                Map.of("projectId", "p2", "stage", "DEV"));

        assertThat(sender.framesOf(subscriber).size()).isEqualTo(1); // 只有初始 ping

        hub.broadcast(NOTIFICATION, "p1", "stage-changed",
                Map.of("projectId", "p1", "stage", "BA"));

        assertThat(sender.framesOf(subscriber)).hasSize(2); // 过滤命中才收到
    }

    @Test
    void given_repeated_broadcasts_when_same_stream_then_seq_monotonic_and_streams_independent() {
        SseChannelHub hub = newHub(Duration.ofSeconds(600));
        SseEmitter emitter = hub.subscribe(NOTIFICATION, payload -> true);
        sender.framesOf(emitter).clear();

        hub.broadcast(NOTIFICATION, "p1", "stage-changed", Map.of("projectId", "p1"));
        hub.broadcast(NOTIFICATION, "p1", "stage-changed", Map.of("projectId", "p1"));
        hub.broadcast(NOTIFICATION, "p2", "stage-changed", Map.of("projectId", "p2"));
        hub.broadcast(NOTIFICATION, "p1", "stage-changed", Map.of("projectId", "p1"));

        assertThat(sender.framesOf(emitter))
                .extracting(SseServerEvent::id)
                .containsExactly("p1:1", "p1:2", "p2:1", "p1:3");
    }

    @Test
    void given_two_channels_when_broadcast_then_only_same_channel_subscribers_notified() {
        SseChannelHub hub = newHub(Duration.ofSeconds(600));
        SseEmitter notificationSub = hub.subscribe(NOTIFICATION, payload -> true);
        SseEmitter agentSub = hub.subscribe(AGENT_STREAM, payload -> true);

        hub.broadcast(NOTIFICATION, "p1", "workspace-created", Map.of("projectId", "p1"));

        assertThat(sender.framesOf(notificationSub).size()).isEqualTo(2); // ping + 事件
        assertThat(sender.framesOf(agentSub).size()).isEqualTo(1);        // 只有 ping
    }

    @Test
    void given_no_subscribers_when_broadcast_then_noop() {
        SseChannelHub hub = newHub(Duration.ofSeconds(600));

        assertThatCode(() -> hub.broadcast(NOTIFICATION, "p1", "workspace-created",
                Map.of("projectId", "p1"))).doesNotThrowAnyException();
        assertThat(sender.attempts()).isZero();
    }

    @Test
    void given_payload_with_type_key_when_broadcast_then_rejected_before_send() {
        // 内核层兜底信封契约（payload 内禁 type 键）：调用方 bug，发射前 fail-fast
        SseChannelHub hub = newHub(Duration.ofSeconds(600));
        SseEmitter emitter = hub.subscribe(NOTIFICATION, payload -> true);

        assertThatThrownBy(() -> hub.broadcast(NOTIFICATION, "p1", "workspace-created",
                Map.of("type", "WEBSITE", "projectId", "p1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.framesOf(emitter)).hasSize(1); // 只有 ping，无事件
    }

    @Test
    void given_subscriber_send_fails_when_broadcast_then_swallowed_and_emitter_evicted() {
        // fire-and-forget（ADR-0001）：发送失败只记日志不影响调用方；坏连接逐出不再重试
        SseChannelHub hub = newHub(Duration.ofSeconds(600));
        SseEmitter broken = hub.subscribe(NOTIFICATION, payload -> true);
        SseEmitter healthy = hub.subscribe(NOTIFICATION, payload -> true);
        sender.breakEmitter(broken);

        assertThatCode(() -> hub.broadcast(NOTIFICATION, "p1", "workspace-created",
                Map.of("projectId", "p1"))).doesNotThrowAnyException();

        // 第二次广播：坏连接已逐出，不再向其发送
        hub.broadcast(NOTIFICATION, "p1", "workspace-destroyed", Map.of("projectId", "p1"));

        assertThat(sender.eventFramesOf(healthy))
                .extracting(SseServerEvent::id)
                .containsExactly("p1:1", "p1:2");
        // broken 仅收到订阅时的初始 ping（当时未断），事件帧一无所获；
        // 发送尝试 = 初始 ping + 第一次广播失败的一次，第二次广播不再尝试
        assertThat(sender.eventFramesOf(broken)).isEmpty();
        assertThat(sender.attemptsFor(broken)).isEqualTo(2);
    }

    @Test
    void given_ping_send_fails_when_heartbeat_then_subscriber_evicted() throws InterruptedException {
        // 短心跳间隔：断连后由心跳失败逐出；逐出后广播不再产生发送尝试
        SseChannelHub hub = newHub(Duration.ofMillis(50));
        SseEmitter broken = hub.subscribe(NOTIFICATION, payload -> true);
        sender.breakEmitter(broken);

        Thread.sleep(200);
        int attemptsAfterHeartbeatFailures = sender.attemptsFor(broken);

        hub.broadcast(NOTIFICATION, "p1", "workspace-created", Map.of("projectId", "p1"));

        assertThat(attemptsAfterHeartbeatFailures).isGreaterThanOrEqualTo(2); // 初始 ping + ≥1 轮失败心跳
        assertThat(sender.attemptsFor(broken)).isEqualTo(attemptsAfterHeartbeatFailures); // 广播 0 尝试
    }

    @Test
    void given_short_heartbeat_interval_when_running_then_periodic_pings_sent() throws InterruptedException {
        SseChannelHub hub = newHub(Duration.ofMillis(50));
        SseEmitter emitter = hub.subscribe(NOTIFICATION, payload -> true);

        Thread.sleep(400);

        long pings = sender.framesOf(emitter).stream()
                .filter(frame -> "ping".equals(frame.comment()))
                .count();
        assertThat(pings).isGreaterThanOrEqualTo(2);
    }

    @Test
    void given_hub_shutdown_when_heartbeat_running_then_pings_stop() throws InterruptedException {
        SseChannelHub hub = newHub(Duration.ofMillis(50));
        SseEmitter emitter = hub.subscribe(NOTIFICATION, payload -> true);

        Thread.sleep(200);
        hub.shutdown();
        int pingsAtShutdown = sender.framesOf(emitter).size();

        Thread.sleep(200);

        assertThat(sender.framesOf(emitter)).hasSize(pingsAtShutdown);
    }
}
