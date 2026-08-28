package com.aieducenter.aiplatform.base.eventhub.infrastructure.sse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    private static final String REPLAYABLE = "replayable";
    private static final Instant FIXED_TS = Instant.parse("2026-08-21T02:15:33.123Z");

    private final RecordingSseSender sender = new RecordingSseSender();
    private final List<SseChannelHub> hubs = new ArrayList<>();

    @AfterEach
    void tearDown() {
        hubs.forEach(SseChannelHub::shutdown);
    }

    private SseChannelHub newHub(Duration heartbeatInterval) {
        return newHub(sender, heartbeatInterval);
    }

    private SseChannelHub newHub(SseSender customSender, Duration heartbeatInterval) {
        SseChannelHub hub = new SseChannelHub(customSender,
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

    // ---------- 近期帧重放（#55：注册 opt-in，通知通道不动） ----------

    @Test
    void given_registered_channel_without_subscribers_when_broadcast_then_late_replay_subscription_receives_filtered_frames() {
        // 事故主场景（#53 spec）：起跑即死的帧在零订阅时发出，晚到订阅仍要看到；
        // 订阅谓词对重放同样过滤命中（别的项目/运行的帧不泄漏）
        SseChannelHub hub = newHub(Duration.ofSeconds(600));
        hub.registerReplay(REPLAYABLE, 100);

        hub.broadcast(REPLAYABLE, "run-1", "error", Map.of("projectId", "p1", "message", "boom"));
        hub.broadcast(REPLAYABLE, "run-9", "error", Map.of("projectId", "p2", "message", "别家的"));
        hub.broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1", "text", "hi"));

        SseEmitter late = hub.subscribe(REPLAYABLE,
                payload -> "p1".equals(payload.get("projectId")), true);

        assertThat(sender.framesOf(late)).hasSize(3); // ping + 命中谓词的两帧
        assertThat(sender.framesOf(late).get(0).comment()).isEqualTo("ping");
        assertThat(sender.eventFramesOf(late))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:1", "run-1:2"); // 原事件 id 重放，p2 帧被谓词滤掉
        assertThat(sender.eventFramesOf(late))
                .extracting(frame -> ((EventEnvelope) frame.data()).type())
                .containsExactly("error", "text-delta");
    }

    @Test
    void given_subscription_between_broadcasts_when_replay_on_then_replay_then_live_without_dup_or_gap() {
        // 接缝核心：订阅夹在两次广播之间——先收重放帧（b1）、再无缝进实时流（b2），
        // id 同一口径、seq 续接、不重不漏不乱序
        SseChannelHub hub = newHub(Duration.ofSeconds(600));
        hub.registerReplay(REPLAYABLE, 100);
        hub.broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1", "n", 1));

        SseEmitter subscriber = hub.subscribe(REPLAYABLE, payload -> true, true);

        hub.broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1", "n", 2));
        hub.broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1", "n", 3));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:1", "run-1:2", "run-1:3");
    }

    @Test
    void given_replay_in_progress_when_live_frame_arrives_then_pending_delivered_after_backlog() {
        // 重放进行中到达的 live 帧进订阅级 pending 队列，重放毕按序补投——
        // 发送缝 hook 在重放首帧下发时同步触发一次广播，确定性命中该窗口
        AtomicBoolean fired = new AtomicBoolean();
        AtomicReference<SseChannelHub> hubRef = new AtomicReference<>();
        SseChannelHub hub = newHub((emitter, event) -> {
            if (event.id() != null && event.id().endsWith(":1") && fired.compareAndSet(false, true)) {
                hubRef.get().broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1", "n", 3));
            }
            sender.send(emitter, event);
        }, Duration.ofSeconds(600));
        hubRef.set(hub);
        hub.registerReplay(REPLAYABLE, 100);
        hub.broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1", "n", 1));
        hub.broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1", "n", 2));

        SseEmitter subscriber = hub.subscribe(REPLAYABLE, payload -> true, true);

        // hook 在「帧已入缓冲、重放发送中」触发的 live 帧经 pending 补投，仍落在全部重放帧之后
        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:1", "run-1:2", "run-1:3");
    }

    @Test
    void given_capacity_two_when_three_broadcasts_then_only_latest_two_replayed() {
        // 有界环形缓冲：容量上界生效、旧帧逐出
        SseChannelHub hub = newHub(Duration.ofSeconds(600));
        hub.registerReplay(REPLAYABLE, 2);

        for (int n = 1; n <= 3; n++) {
            hub.broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1", "n", n));
        }

        SseEmitter late = hub.subscribe(REPLAYABLE, payload -> true, true);

        assertThat(sender.eventFramesOf(late))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:2", "run-1:3"); // run-1:1 已被逐出
    }

    @Test
    void given_replay_off_when_subscribe_then_no_replay_and_live_still_flows() {
        // 重放开关关 = 现行为：连接前已发出的帧拿不到（注册通道也不补），之后照常实时收
        SseChannelHub hub = newHub(Duration.ofSeconds(600));
        hub.registerReplay(REPLAYABLE, 100);
        hub.broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1"));

        SseEmitter subscriber = hub.subscribe(REPLAYABLE, payload -> true, false);

        assertThat(sender.eventFramesOf(subscriber)).isEmpty(); // 只有初始 ping，无重放

        hub.broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:2"); // seq 照常分配（零订阅时也已入缓冲计数）
    }

    @Test
    void given_unregistered_channel_when_subscribe_with_replay_then_current_behavior() {
        // 未注册通道不受重放开关影响（注册面即「哪些通道补发」的唯一定义处）
        SseChannelHub hub = newHub(Duration.ofSeconds(600));

        assertThatCode(() -> hub.broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1")))
                .doesNotThrowAnyException(); // 零订阅 noop（未注册不缓冲）

        SseEmitter subscriber = hub.subscribe(REPLAYABLE, payload -> true, true);

        assertThat(sender.eventFramesOf(subscriber)).isEmpty();

        hub.broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1"));

        assertThat(sender.eventFramesOf(subscriber))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:1"); // 订阅后才 seq 从 1 起（未注册零订阅不分配）
    }

    @Test
    void given_registered_channel_when_broadcast_then_zero_subscriber_frames_still_buffered_with_seq() {
        // 已订阅者视角：注册通道上重放订阅不干扰既有实时订阅；缓冲中的帧对后到者可见
        SseChannelHub hub = newHub(Duration.ofSeconds(600));
        hub.registerReplay(REPLAYABLE, 100);
        SseEmitter first = hub.subscribe(REPLAYABLE, payload -> true);
        sender.framesOf(first).clear();

        hub.broadcast(REPLAYABLE, "run-1", "text-delta", Map.of("projectId", "p1", "n", 1));

        SseEmitter second = hub.subscribe(REPLAYABLE, payload -> true, true);

        assertThat(sender.eventFramesOf(first))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:1"); // 既有订阅实时收到，不受重放影响
        assertThat(sender.eventFramesOf(second))
                .extracting(SseServerEvent::id)
                .containsExactly("run-1:1"); // 晚到重放订阅收到同一帧、同一 id（跨订阅各投一次，不算重复）
    }

    @Test
    void given_register_replay_when_capacity_invalid_then_fail_fast() {
        SseChannelHub hub = newHub(Duration.ofSeconds(600));

        assertThatThrownBy(() -> hub.registerReplay(REPLAYABLE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_replay_already_registered_when_register_again_then_fail_fast() {
        // 注册面是「哪些通道补发」的唯一定义处，重复注册按调用方 bug fail-fast
        SseChannelHub hub = newHub(Duration.ofSeconds(600));
        hub.registerReplay(REPLAYABLE, 100);

        assertThatThrownBy(() -> hub.registerReplay(REPLAYABLE, 200))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void given_concurrent_broadcasts_and_late_replay_subscription_when_settled_then_no_dup_no_gap()
            throws Exception {
        // 并发接缝压测：4 广播线程 × 50 帧与晚到重放订阅赛跑——收到的 id 无重复、
        // 无缺口（每帧恰得其一：重放快照 / pending 补投 / 直发）。注意：并发广播线程
        // 间的到达序本就无 FIFO 保证（订阅级锁只串行化发送，与既有内核一致），故
        // 不断言整体有序——「重放 → live」的顺序契约由上方串行用例锁定
        // （记录缝需线程安全，故本用例自带 sender）
        List<SseServerEvent> frames = new CopyOnWriteArrayList<>();
        SseChannelHub hub = newHub((emitter, event) -> {
            if (event.id() != null) {
                frames.add(event);
            }
        }, Duration.ofSeconds(600));
        hub.registerReplay(REPLAYABLE, 500);

        int broadcasterThreads = 4;
        int framesPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(broadcasterThreads + 1);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> jobs = new ArrayList<>();
            for (int t = 0; t < broadcasterThreads; t++) {
                int threadIndex = t;
                jobs.add(pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < framesPerThread; n++) {
                        hub.broadcast(REPLAYABLE, "run-1", "text-delta",
                                Map.of("projectId", "p1", "thread", threadIndex, "n", n));
                    }
                    return null;
                }));
            }
            Future<SseEmitter> subscription = pool.submit(() -> {
                start.await();
                return hub.subscribe(REPLAYABLE, payload -> true, true);
            });
            start.countDown();
            for (Future<?> job : jobs) {
                job.get(10, TimeUnit.SECONDS);
            }
            subscription.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        int total = broadcasterThreads * framesPerThread;
        List<Long> seqs = frames.stream()
                .map(frame -> Long.parseLong(frame.id().substring("run-1:".length())))
                .toList();
        assertThat(seqs).doesNotHaveDuplicates();
        Set<Long> expected = IntStream.rangeClosed(1, total)
                .mapToObj(Long::valueOf)
                .collect(Collectors.toSet());
        assertThat(seqs.stream().collect(Collectors.toSet()))
                .isEqualTo(expected); // 缓冲容量 ≥ 总帧数：1..total 一帧不少一帧不多
    }

    @Test
    void given_concurrent_broadcasts_when_replay_after_settled_then_replayed_in_id_order()
            throws Exception {
        // seq 分配与入缓冲同临界区的回归锁：并发广播线程的帧在缓冲里必须按 seq 序
        // 排列（谁的 incrementAndGet 在前谁先进），全部落定后晚到重放订阅收到的
        // 帧严格按 id 递增——重放流自身不乱序（live 直发交错是另一回事，见上用例）
        List<SseServerEvent> frames = new CopyOnWriteArrayList<>();
        SseChannelHub hub = newHub((emitter, event) -> {
            if (event.id() != null) {
                frames.add(event);
            }
        }, Duration.ofSeconds(600));
        hub.registerReplay(REPLAYABLE, 500);

        int broadcasterThreads = 4;
        int framesPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(broadcasterThreads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> jobs = new ArrayList<>();
            for (int t = 0; t < broadcasterThreads; t++) {
                int threadIndex = t;
                jobs.add(pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < framesPerThread; n++) {
                        hub.broadcast(REPLAYABLE, "run-1", "text-delta",
                                Map.of("projectId", "p1", "thread", threadIndex, "n", n));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> job : jobs) {
                job.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        hub.subscribe(REPLAYABLE, payload -> true, true);

        int total = broadcasterThreads * framesPerThread;
        List<Long> seqs = frames.stream()
                .map(frame -> Long.parseLong(frame.id().substring("run-1:".length())))
                .toList();
        assertThat(seqs).isSorted();  // 重放流按 id 序：缓冲序 == seq 序
        assertThat(seqs.stream().collect(Collectors.toSet()))
                .isEqualTo(IntStream.rangeClosed(1, total)
                        .mapToObj(Long::valueOf)
                        .collect(Collectors.toSet()));
    }
}
