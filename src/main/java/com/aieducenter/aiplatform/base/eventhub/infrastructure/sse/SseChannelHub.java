package com.aieducenter.aiplatform.base.eventhub.infrastructure.sse;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aieducenter.aiplatform.base.eventhub.domain.model.EventEnvelope;
import com.aieducenter.aiplatform.base.eventhub.domain.model.SseEventId;
/**
 * SSE 传输内核（ADR-0001 落码归属·片1）：emitter 管理 / 心跳 / predicate 过滤订阅 /
 * 信封与 id 分配 / fire-and-forget 广播。内存单实例起步（重启丢事件，通知通道本就
 * 永不补发；多实例化见 B0 蓝图 §3 升级路径）。
 *
 * <p>通道按名泛化，零业务概念：通道语义（路径、关联字段、streamId 取值）在应用层
 * （如 {@code PlatformNotificationAppService}），片2a 的 agent 流通道复用本内核。
 * 双通道只共用本传输内核，是两回事。将来提取为 cartisan-boot 模块（拟名
 * cartisan-sse）时，本类整体迁出。</p>
 *
 * <p>线程模型：广播在调用方线程同步扇出（内存内，快）；心跳由单线程
 * {@code sse-heartbeat} 周期执行。对同一 emitter 的并发发送经订阅级
 * {@link ReentrantLock} 串行，心跳遇锁即跳过（该连接正有事件在发，即存活）。</p>
 *
 * @since 0.1.0
 */
@Slf4j
@Component
public class SseChannelHub {

    /** SSE name 恒为 event（两通道统一信封，前端每通道一个 listener）。 */
    public static final String EVENT_NAME = "event";

    /** 心跳注释行：每 15s 发 {@code :ping}，防代理掐空闲连接（ADR-0001）。 */
    public static final String PING_COMMENT = "ping";

    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final SseSender sender;
    private final Clock clock;
    private final Map<String, ChannelState> channels = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor;

    @Autowired
    public SseChannelHub() {
        this(SseSender.DIRECT, Clock.systemUTC(), DEFAULT_HEARTBEAT_INTERVAL);
    }

    public SseChannelHub(SseSender sender, Clock clock, Duration heartbeatInterval) {
        this.sender = sender;
        this.clock = clock;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatExecutor.scheduleAtFixedRate(this::pingAll,
                heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 订阅一个通道。filter 为订阅过滤谓词（作用于事件 payload），null 视为全量。
     * 不超时（断连由心跳发送失败逐出）；连接建立即刻发一帧 {@code :ping}，
     * 冲刷响应头并作即时存活信号。
     */
    public SseEmitter subscribe(String channel, Predicate<Map<String, Object>> filter) {
        Predicate<Map<String, Object>> effectiveFilter = filter == null ? payload -> true : filter;
        ChannelState state = channels.computeIfAbsent(channel, key -> new ChannelState());
        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(channel, emitter, effectiveFilter);

        state.subscriptions.add(subscription);
        emitter.onCompletion(() -> state.subscriptions.remove(subscription));
        emitter.onTimeout(() -> state.subscriptions.remove(subscription));
        emitter.onError(throwable -> state.subscriptions.remove(subscription));

        sendOrEvict(state, subscription, SseServerEvent.comment(PING_COMMENT));
        return emitter;
    }

    /**
     * 广播一帧事件到通道内所有过滤命中的订阅。fire-and-forget：单订阅发送失败只记
     * 日志并逐出，绝不影响调用方与其他订阅；信封契约违约（如 payload 内含 type 键）
     * 属调用方 bug，发射前 fail-fast 抛 IllegalArgumentException。
     *
     * @param streamId 事件归属的流标识（通知通道=projectId，agent 流通道=runId），
     *                 id 行取 {@code {streamId}:{seq}}，seq 同流内单调递增
     */
    public void broadcast(String channel, String streamId, String type, Map<String, Object> payload) {
        EventEnvelope envelope = new EventEnvelope(type, payload, clock.instant());
        ChannelState state = channels.get(channel);
        if (state == null || state.subscriptions.isEmpty()) {
            return;
        }
        long seq = state.sequences
                .computeIfAbsent(streamId, key -> new AtomicLong())
                .incrementAndGet();
        SseServerEvent frame = SseServerEvent.of(new SseEventId(streamId, seq).value(), EVENT_NAME, envelope);

        for (Subscription subscription : state.subscriptions) {
            if (!subscription.filter.test(payload)) {
                continue;
            }
            sendOrEvict(state, subscription, frame);
        }
    }

    private void sendOrEvict(ChannelState state, Subscription subscription, SseServerEvent event) {
        subscription.lock.lock();
        try {
            sender.send(subscription.emitter, event);
        } catch (Exception ex) {
            log.warn("SSE 事件发送失败，逐出订阅（channel={}）：{}", subscription.channel, ex.getMessage());
            evict(state, subscription);
        } finally {
            subscription.lock.unlock();
        }
    }

    private void pingAll() {
        // 周期任务一次未捕获异常即终止调度（JDK 语义），整体兜底
        try {
            for (ChannelState state : channels.values()) {
                for (Subscription subscription : state.subscriptions) {
                    if (!subscription.lock.tryLock()) {
                        continue;
                    }
                    try {
                        sender.send(subscription.emitter, SseServerEvent.comment(PING_COMMENT));
                    } catch (Exception ex) {
                        log.debug("SSE 心跳失败，逐出订阅（channel={}）：{}",
                                subscription.channel, ex.getMessage());
                        evict(state, subscription);
                    } finally {
                        subscription.lock.unlock();
                    }
                }
            }
        } catch (Throwable ex) {
            log.warn("SSE 心跳轮询异常（已忽略，下轮继续）", ex);
        }
    }

    private void evict(ChannelState state, Subscription subscription) {
        state.subscriptions.remove(subscription);
        try {
            subscription.emitter.complete();
        } catch (Exception ex) {
            log.debug("SSE 逐出时 complete 失败（连接已断，忽略）：{}", ex.getMessage());
        }
    }

    /**
     * 停止心跳并释放线程（容器关闭时调用；幂等）。
     */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    /**
     * 一个订阅：emitter + 过滤谓词；lock 串行化对同一 emitter 的并发发送
     * （广播线程 vs 心跳线程）——SseEmitter 非线程安全。
     */
    private static final class Subscription {
        private final String channel;
        private final SseEmitter emitter;
        private final Predicate<Map<String, Object>> filter;
        private final ReentrantLock lock = new ReentrantLock();

        private Subscription(String channel, SseEmitter emitter, Predicate<Map<String, Object>> filter) {
            this.channel = channel;
            this.emitter = emitter;
            this.filter = filter;
        }
    }

    private static final class ChannelState {
        private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
        private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    }
}
