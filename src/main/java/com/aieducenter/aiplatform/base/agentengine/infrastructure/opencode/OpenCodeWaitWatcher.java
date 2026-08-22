package com.aieducenter.aiplatform.base.agentengine.infrastructure.opencode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.opencode.OpenCodeServeBootstrap.ServeEndpoint;

/**
 * opencode 等待点发现通道（片2b）：run 存续期盯住引擎的问答与权限挂起，检出即以
 * {@code wait-raised} 平台事件经 sink 上报（落库归 agentengine 应用层的流桥）。
 * 三路互补——opencode 1.18 两类挂起的暴露面不同（已核对 /doc OpenAPI 与事件总线）：
 *
 * <ul>
 *   <li><b>权限 · 兜底</b>：全局 {@code GET /permission} 轮询——列表端点 1.18 实测
 *       存在（票 #35：本类旧注释「无列表端点可轮询」不成立），与问答轮询同构，是
 *       权限发现的可靠底座；</li>
 *   <li><b>权限 · 快路</b>：事件总线 {@code GET /event}（SSE）的 {@code permission.updated}
 *       帧（全量 Permission 载荷）——检出时延更低，但总线路径丢帧未定性（#35 冒烟
 *       两次权限挂起平台全盲、手动订阅却健康），只作加速不作依赖；与轮询路经
 *       {@code reportedRefs} 去重，应用层 raise 幂等兜底双报；</li>
 *   <li><b>问答</b>：全局 {@code GET /question}（que_* 机制）轮询——问答不上总线。</li>
 * </ul>
 *
 * <p>生命周期与 run 同界：随消息发送起跑、run 结束（含失败/终止）即停，不做常驻
 * 连接管理；连接抖动自愈（重连/重轮询），引擎不可达静默等下一轮（问答/权限轮询沿用
 * {@code pendingQuestions} 的容错语义）。平台重启期间检出的挂起不补录（run 已死，
 * 孤儿问答经会话复用清理或引擎侧超时收敛——Phase A 容忍，见票 #21 备注）。</p>
 */
@Slf4j
final class OpenCodeWaitWatcher {

    /** opencode 事件总线帧的载荷键（1.18 wire 格式 {id, type, properties}）。 */
    private static final String BUS_EVENT_PERMISSION = "permission.updated";

    /** 问答轮询间隔（问答无总线事件，轮询是唯一通道；秒级足够 HITL 场景）。 */
    private static final Duration QUESTION_POLL_INTERVAL = Duration.ofSeconds(2);

    /** 权限轮询间隔（票 #35：/permission 列表轮询为权限发现的兜底通道，与问答同构）。 */
    private static final Duration PERMISSION_POLL_INTERVAL = Duration.ofSeconds(2);

    /** 总线重连退避（连接被引擎关闭/网络抖动后）。 */
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(1);

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final ServeEndpoint endpoint;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String runId;
    private final String sessionId;
    private final Consumer<AgentEvent> sink;
    /** 已上报的引擎侧引用（permission.updated 会重复推、问答会重复轮到）。 */
    private final Set<String> reportedRefs = ConcurrentHashMap.newKeySet();
    /** 在飞的总线订阅（stop 时 cancel 以掐断长连接，防泄漏）。 */
    private volatile CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> inFlightBus;
    private final Thread busListener;
    private final Thread permissionPoller;
    private final Thread questionPoller;
    private volatile boolean running = true;

    private OpenCodeWaitWatcher(ServeEndpoint endpoint, HttpClient http, ObjectMapper mapper,
                                String runId, String sessionId, Consumer<AgentEvent> sink) {
        this.endpoint = endpoint;
        this.http = http;
        this.mapper = mapper;
        this.runId = runId;
        this.sessionId = sessionId;
        this.sink = sink;
        this.busListener = Thread.ofPlatform().daemon()
                .name("opencode-wait-bus-" + THREAD_SEQ.incrementAndGet())
                .start(this::listenEventBus);
        this.permissionPoller = Thread.ofPlatform().daemon()
                .name("opencode-wait-perm-" + THREAD_SEQ.incrementAndGet())
                .start(this::pollPermissions);
        this.questionPoller = Thread.ofPlatform().daemon()
                .name("opencode-wait-poll-" + THREAD_SEQ.incrementAndGet())
                .start(this::pollQuestions);
    }

    /**
     * 起跑 run 级发现通道（消息发送前调用；run 结束 finally 里 {@link #stop()}）。
     */
    static OpenCodeWaitWatcher start(ServeEndpoint endpoint, HttpClient http,
                                     ObjectMapper mapper, String runId, String sessionId,
                                     Consumer<AgentEvent> sink) {
        return new OpenCodeWaitWatcher(endpoint, http, mapper, runId, sessionId, sink);
    }

    /**
     * 停止：置停标志 + 掐断在飞总线订阅 + 中断休眠，线程随 run 收尾退出。
     */
    void stop() {
        running = false;
        CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> inFlight = inFlightBus;
        if (inFlight != null) {
            inFlight.cancel(true);
        }
        busListener.interrupt();
        permissionPoller.interrupt();
        questionPoller.interrupt();
    }

    // ---------- 权限：事件总线（快路） ----------

    private void listenEventBus() {
        while (running) {
            CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> future = null;
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(endpoint.baseUrl() + "/event"))
                        .header("Authorization", endpoint.authHeader())
                        .header("Accept", "text/event-stream")
                        .GET().build();
                // sendAsync + ofLines：headers 到达即取 body 流，行随总线帧渐进送达；
                // 不设请求级 timeout——SSE 是长连接，timeout 会掐断流（#35 嫌疑点，
                // 检出兜底已归轮询路，连接级超时由 HttpClient connectTimeout 承担）；
                // future 留柄供 stop 掐断（cancel 会关闭连接，防 run 结束后长连泄漏）
                future = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
                inFlightBus = future;
                HttpResponse<java.util.stream.Stream<String>> response = future.get();
                try (java.util.stream.Stream<String> lines = response.body()) {
                    lines.takeWhile(line -> running).forEach(this::handleBusLine);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // stop() 主动掐断
            } catch (Exception e) {
                // 连接抖动/引擎暂不可达：退避重连（总线只是快路，权限检出兜底在轮询路；
                // #35：info 级留痕——冒烟曾因 debug 级不可见漏判断连）。stop() 掐断
                // 产生的取消异常不算断连，running=false 不记
                if (running) {
                    log.info("[agentengine] opencode 事件总线断开重连（run {}）：{}", runId,
                            e.getMessage());
                }
            } finally {
                if (future != null) {
                    future.cancel(true);
                }
            }
            if (running) {
                sleepQuietly(RECONNECT_BACKOFF);
            }
        }
    }

    private void handleBusLine(String line) {
        if (!line.startsWith("data:")) {
            return; // event:/id:/注释行不入料
        }
        JsonNode event;
        try {
            event = mapper.readTree(line.substring("data:".length()).strip());
        } catch (IOException e) {
            return; // 非 JSON 行（keep-alive 等）忽略
        }
        if (!BUS_EVENT_PERMISSION.equals(event.path("type").asText())) {
            return; // 问答不上总线（1.18），其余总线事件非本通道职责
        }
        JsonNode permission = event.path("properties");
        if (!sessionId.equals(permission.path("sessionID").asText())) {
            return;
        }
        String permissionId = permission.path("id").asText("");
        if (permissionId.isBlank() || !reportedRefs.add(permissionId)) {
            return; // 重复推（permission 状态更新会重发全量）
        }
        emit(WaitKind.PERMISSION, permissionId, summaryOf(permission),
                mapper.convertValue(permission, Map.class));
    }

    // ---------- 权限：全局 permission 轮询（兜底） ----------

    private void pollPermissions() {
        while (running) {
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(endpoint.baseUrl() + "/permission"))
                        .header("Authorization", endpoint.authHeader())
                        .timeout(PROBE_TIMEOUT)
                        .GET().build();
                HttpResponse<String> response = http.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 300) {
                    reportPermissions(response.body());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                // 引擎暂不可达：静默等下一轮（容错语义同问答轮询）
                log.debug("[agentengine] opencode 权限轮询失败（run {}）：{}", runId,
                        e.getMessage());
            }
            sleepQuietly(PERMISSION_POLL_INTERVAL);
        }
    }

    private void reportPermissions(String body) {
        JsonNode permissions;
        try {
            permissions = mapper.readTree(body);
        } catch (IOException e) {
            return;
        }
        if (!permissions.isArray()) {
            return;
        }
        for (JsonNode permission : permissions) {
            if (!sessionId.equals(permission.path("sessionID").asText())) {
                continue; // 全局端点，按会话过滤（与 pendingQuestions 同口径）
            }
            String permissionId = permission.path("id").asText("");
            if (permissionId.isBlank() || !reportedRefs.add(permissionId)) {
                continue; // 与总线快路/前轮轮询重复
            }
            emit(WaitKind.PERMISSION, permissionId, summaryOf(permission),
                    mapper.convertValue(permission, Map.class));
        }
    }

    // ---------- 问答：全局 question 轮询 ----------

    private void pollQuestions() {
        while (running) {
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(endpoint.baseUrl() + "/question"))
                        .header("Authorization", endpoint.authHeader())
                        .timeout(PROBE_TIMEOUT)
                        .GET().build();
                HttpResponse<String> response = http.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 300) {
                    reportQuestions(response.body());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                // 引擎暂不可达：静默等下一轮（问答轮询的容错语义同 pendingQuestions）
                log.debug("[agentengine] opencode 问答轮询失败（run {}）：{}", runId,
                        e.getMessage());
            }
            sleepQuietly(QUESTION_POLL_INTERVAL);
        }
    }

    private void reportQuestions(String body) {
        JsonNode questions;
        try {
            questions = mapper.readTree(body);
        } catch (IOException e) {
            return;
        }
        if (!questions.isArray()) {
            return;
        }
        for (JsonNode question : questions) {
            if (!sessionId.equals(question.path("sessionID").asText())) {
                continue; // 全局端点，按会话过滤（与 pendingQuestions 同口径）
            }
            String requestId = question.path("id").asText("");
            if (requestId.isBlank() || !reportedRefs.add(requestId)) {
                continue;
            }
            emit(WaitKind.QUESTION, requestId, questionSummary(question),
                    mapper.convertValue(question, Map.class));
        }
    }

    // ---------- 上报 ----------

    private void emit(WaitKind kind, String engineRef, String summary, Map<String, Object> body) {
        log.info("[agentengine] 发现等待点 kind={} ref={} session={} run={}",
                kind, engineRef, sessionId, runId);
        sink.accept(new AgentEvent(AgentEventTypes.WAIT_RAISED, Map.of(
                AgentEventTypes.WAIT_RUN_FIELD, runId,
                AgentEventTypes.WAIT_SESSION_FIELD, sessionId,
                "engine", OpenCodeAdapter.ENGINE,
                AgentEventTypes.WAIT_KIND_FIELD, kind.name(),
                AgentEventTypes.WAIT_SUMMARY_FIELD, summary == null ? "" : summary,
                AgentEventTypes.WAIT_ENGINE_REF_FIELD, engineRef,
                AgentEventTypes.WAIT_DATA_FIELD, body)));
    }

    /** 权限中性短文本：title 字段（opencode Permission 载荷自带）。 */
    private static String summaryOf(JsonNode permission) {
        String title = permission.path("title").asText("");
        return title.isBlank() ? "权限请求" : title;
    }

    /** 问答中性短文本：首个问题的 question 文本（截断保短）。 */
    private static String questionSummary(JsonNode question) {
        String text = question.path("questions").path(0).path("question").asText("");
        if (text.isBlank()) {
            return "智能体提问";
        }
        return text.length() > 100 ? text.substring(0, 100) : text;
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
