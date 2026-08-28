package com.aieducenter.aiplatform.base.eventhub.infrastructure.sse;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * 信封与 id 分配 / fire-and-forget 广播 / 近期帧重放（注册 opt-in）。内存单实例起步
 * （重启丢事件，通知通道本就永不补发；多实例化见 B0 蓝图 §3 升级路径）。
 *
 * <p>通道按名泛化，零业务概念：通道语义（路径、关联字段、streamId 取值）在应用层
 * （如 {@code PlatformNotificationAppService}），片2a 的 agent 流通道复用本内核。
 * 双通道只共用本传输内核，是两回事。将来提取为 cartisan-boot 模块（拟名
 * cartisan-sse）时，本类整体迁出。</p>
 *
 * <p>近期帧重放（{@code Flux.replay(N)} 语义）：通道经 {@link #registerReplay} 显式
 * 注册（携带容量）后，广播帧一律进 per-channel 有界环形缓冲——零订阅时也入缓冲、
 * seq 照常分配；新订阅（重放开）先收命中订阅谓词的最近帧（原事件 id）再进实时流。
 * 未注册通道「永不补发」语义分毫不变（注册面即「哪些通道补发」的唯一定义处）。
 * 缓冲不追终态、不按 runId 分桶、单实例内存态。</p>
 *
 * <p>线程模型：广播在调用方线程同步扇出（内存内，快）；心跳由单线程
 * {@code sse-heartbeat} 周期执行。对同一 emitter 的并发发送经订阅级
 * {@link ReentrantLock} 串行，心跳遇锁即跳过（该连接正有事件在发，即存活）。
 * 重放接缝：通道级锁互斥「缓冲快照+订阅注册」与「入缓冲+订阅快照」，网络发送一律
 * 锁外；重放进行中的订阅，live 帧进订阅级 pending 队列、重放毕按序补投——重放与
 * 实时流不重不漏不乱序。</p>
 *
 * <p>已知取舍：重放补投在订阅建立的调用方线程上同步执行，慢客户端会拖住对同一通道
 * 的并发广播（订阅级锁串行发送）与该调用线程；升级路径为订阅级队列化发送（专职
 * 单消费者线程），届时本类对外契约不变。</p>
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
     * 注册通道开启近期帧重放（opt-in，#55）：此后该通道的广播帧（含零订阅时）一律
     * 进入 per-channel 有界环形缓冲（容量 capacity，旧帧逐出），携带重放开的新订阅
     * 先收命中订阅谓词的最近帧再进实时流。未注册通道「永不补发」语义不动。
     *
     * <p>须先于该通道的订阅/广播使用（生产接线在启动期调用）；容量非正、重复注册均
     * 属调用方 bug，fail-fast。缓冲为单实例内存态，不感知终态、不按 runId 分桶。</p>
     */
    public void registerReplay(String channel, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("重放缓冲容量必须为正整数（channel=" + channel + "）");
        }
        channels.compute(channel, (key, state) -> {
            if (state == null) {
                return ChannelState.withReplay(capacity);
            }
            if (state.replayBuffer != null) {
                throw new IllegalStateException("通道重放缓冲已注册，禁止重复注册（channel=" + channel + "）");
            }
            state.replayBuffer = new ReplayBuffer(capacity);
            return state;
        });
    }

    /**
     * 订阅一个通道（重放开关关）。见 {@link #subscribe(String, Predicate, boolean)}。
     */
    public SseEmitter subscribe(String channel, Predicate<Map<String, Object>> filter) {
        return subscribe(channel, filter, false);
    }

    /**
     * 订阅一个通道。filter 为订阅过滤谓词（作用于事件 payload），null 视为全量。
     * 不超时（断连由心跳发送失败逐出）；连接建立即刻发一帧 {@code :ping}，
     * 冲刷响应头并作即时存活信号。
     *
     * <p>replay=true 且通道已注册重放缓冲（{@link #registerReplay}）：先收命中订阅
     * 谓词的最近缓冲帧（原事件 id，与实时帧同一 id 口径）、再无缝进实时流。replay
     * 开关对未注册通道无效果（现行为）。</p>
     */
    public SseEmitter subscribe(String channel, Predicate<Map<String, Object>> filter, boolean replay) {
        Predicate<Map<String, Object>> effectiveFilter = filter == null ? payload -> true : filter;
        ChannelState state = channels.computeIfAbsent(channel, key -> new ChannelState());
        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(channel, emitter, effectiveFilter);

        List<SseServerEvent> backlog = snapshotAndRegister(state, subscription, replay);
        emitter.onCompletion(() -> state.subscriptions.remove(subscription));
        emitter.onTimeout(() -> state.subscriptions.remove(subscription));
        emitter.onError(throwable -> state.subscriptions.remove(subscription));

        sendOrEvict(state, subscription, SseServerEvent.comment(PING_COMMENT));
        deliverBacklog(state, subscription, backlog);
        return emitter;
    }

    /**
     * 重放开且通道已注册缓冲：通道临界区内完成「缓冲快照+订阅注册」（与广播侧
     * 「入缓冲+订阅快照」互斥——接缝不重不漏的关键：帧要么在快照里、要么在广播
     * 遍历集合里，恰得其一）。订阅先标记 replaying 再注册，此后广播帧先入订阅级
     * pending 队列。否则直接注册（现行为）。
     */
    private List<SseServerEvent> snapshotAndRegister(ChannelState state, Subscription subscription, boolean replay) {
        ReplayBuffer buffer = state.replayBuffer;
        if (!replay || buffer == null) {
            state.subscriptions.add(subscription);
            return List.of();
        }
        subscription.replaying = true;
        state.channelLock.lock();
        try {
            List<SseServerEvent> snapshot = buffer.snapshot();
            state.subscriptions.add(subscription);
            return snapshot;
        } finally {
            state.channelLock.unlock();
        }
    }

    /**
     * 补投重放帧并接管重放期间到达的 live 帧：发完一批、订阅级锁内取下一批 pending，
     * 取空才切 replaying=false——重放期间到达的帧必然进过 pending（不漏），任何直发
     * live 帧必然晚于全部补投帧（不乱序）。谓词过滤在锁外做（帧不可变，等价且临界区
     * 最小化）。
     */
    private void deliverBacklog(ChannelState state, Subscription subscription, List<SseServerEvent> backlog) {
        if (!subscription.replaying) {
            return;
        }
        List<SseServerEvent> batch = backlog;
        while (true) {
            for (SseServerEvent frame : batch) {
                if (subscription.filter.test(payloadOf(frame))) {
                    sendOrEvict(state, subscription, frame);
                }
            }
            subscription.lock.lock();
            try {
                if (subscription.pending.isEmpty()) {
                    subscription.replaying = false;
                    return;
                }
                batch = new ArrayList<>(subscription.pending);
                subscription.pending.clear();
            } finally {
                subscription.lock.unlock();
            }
        }
    }

    /**
     * 广播一帧事件到通道内所有过滤命中的订阅。fire-and-forget：单订阅发送失败只记
     * 日志并逐出，绝不影响调用方与其他订阅；信封契约违约（如 payload 内含 type 键）
     * 属调用方 bug，发射前 fail-fast 抛 IllegalArgumentException。
     *
     * <p>注册了重放缓冲的通道：帧入 per-channel 有界缓冲（零订阅时也入，seq 照常
     * 分配——重放帧与实时帧同一 id 口径）；未注册通道零订阅时 noop、不分配 seq
     * （现行为）。</p>
     *
     * @param streamId 事件归属的流标识（通知通道=projectId，agent 流通道=runId），
     *                 id 行取 {@code {streamId}:{seq}}，seq 同流内单调递增
     */
    public void broadcast(String channel, String streamId, String type, Map<String, Object> payload) {
        EventEnvelope envelope = new EventEnvelope(type, payload, clock.instant());
        ChannelState state = channels.get(channel);
        if (state == null) {
            return;
        }
        ReplayBuffer buffer = state.replayBuffer;
        if (buffer == null && state.subscriptions.isEmpty()) {
            return;
        }
        SseServerEvent frame;
        List<Subscription> subscribers;
        state.channelLock.lock();
        try {
            // seq 分配与入缓冲同临界区：缓冲序 == seq 序（并发广播下重放流仍按 id 序，
            // 容量逐出的也恒为最旧帧）
            long seq = state.sequences
                    .computeIfAbsent(streamId, key -> new AtomicLong())
                    .incrementAndGet();
            frame = SseServerEvent.of(new SseEventId(streamId, seq).value(), EVENT_NAME, envelope);
            if (buffer != null) {
                buffer.append(frame);
            }
            subscribers = List.copyOf(state.subscriptions);
        } finally {
            state.channelLock.unlock();
        }

        for (Subscription subscription : subscribers) {
            if (!subscription.filter.test(payload)) {
                continue;
            }
            enqueueOrSend(state, subscription, frame);
        }
    }

    /** 重放进行中的订阅：帧入订阅级 pending 队列（重放毕按序补投）；否则直接发送。 */
    private void enqueueOrSend(ChannelState state, Subscription subscription, SseServerEvent frame) {
        subscription.lock.lock();
        try {
            if (subscription.replaying) {
                subscription.pending.add(frame);
                return;
            }
        } finally {
            subscription.lock.unlock();
        }
        sendOrEvict(state, subscription, frame);
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

    private static Map<String, Object> payloadOf(SseServerEvent frame) {
        return ((EventEnvelope) frame.data()).payload();
    }

    /**
     * 一个订阅：emitter + 过滤谓词；lock 串行化对同一 emitter 的并发发送
     * （广播线程 vs 心跳线程）——SseEmitter 非线程安全。重放进行中（replaying，
     * 仅注册通道+重放开的新订阅有此窗口）时，广播帧先入 pending、重放毕按序补投；
     * 「入 pending」与「切 replaying=false + 取走 pending」经同一把 lock 互斥。
     */
    private static final class Subscription {
        private final String channel;
        private final SseEmitter emitter;
        private final Predicate<Map<String, Object>> filter;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean replaying;
        private final List<SseServerEvent> pending = new ArrayList<>();

        private Subscription(String channel, SseEmitter emitter, Predicate<Map<String, Object>> filter) {
            this.channel = channel;
            this.emitter = emitter;
            this.filter = filter;
        }
    }

    private static final class ChannelState {
        private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
        private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
        /** 通道临界区：互斥「缓冲快照+订阅注册」与「入缓冲+订阅快照」（重放接缝）。 */
        private final ReentrantLock channelLock = new ReentrantLock();
        /** null = 未注册重放（永不缓冲）；volatile——注册先于订阅/广播使用（启动期）。 */
        private volatile ReplayBuffer replayBuffer;

        private static ChannelState withReplay(int capacity) {
            ChannelState state = new ChannelState();
            state.replayBuffer = new ReplayBuffer(capacity);
            return state;
        }
    }

    /** per-channel 有界环形缓冲（帧不可变，快照与追加可安全共享）。 */
    private static final class ReplayBuffer {
        private final int capacity;
        private final Deque<SseServerEvent> frames = new ArrayDeque<>();

        private ReplayBuffer(int capacity) {
            this.capacity = capacity;
        }

        /** 追加并按容量逐出最旧帧。 */
        private void append(SseServerEvent frame) {
            frames.addLast(frame);
            while (frames.size() > capacity) {
                frames.pollFirst();
            }
        }

        private List<SseServerEvent> snapshot() {
            return List.copyOf(frames);
        }
    }
}
