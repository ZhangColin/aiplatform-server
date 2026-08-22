package com.aieducenter.aiplatform.base.agentengine.infrastructure.opencode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.RunResult;
import com.aieducenter.aiplatform.base.agentengine.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.AgentApiKeyResolver;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.AgentModelConfig;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenCode 适配器全链路（票 #20 验收）：本地 HTTP 假 serve 顶替容器内 opencode——
 * 事件流透传（task-start → part 流 → task-finish）、run 级恰一条 UsageEvent
 * （逐步增量求和）、sessionId 复用、问答/权限交互、引擎故障路径。
 * 引导缝以子类顶替（serve 已就绪 → 拉起路径不触发）。
 */
class OpenCodeAdapterTest {

    private static final String SESSION_ID = "ses_stub_1";

    private HttpServer server;
    private StubServe serve;
    private RecordingUsageSink usageSink;
    private OpenCodeAdapter adapter;
    private WorkspaceHandle handle;

    @BeforeEach
    void setUp() throws IOException {
        serve = new StubServe();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", serve);
        // 事件总线的 /event 帧是长连接（handler 阻塞发送）——线程池化防饿死其他端点
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        int port = server.getAddress().getPort();

        usageSink = new RecordingUsageSink();
        adapter = new OpenCodeAdapter(
                new PreloadedBootstrap("http://localhost:" + port, "stub-password"),
                new AgentModelConfig("deepseek", "deepseek-v4-pro"),
                usageSink, 30, Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC));
        handle = WorkspaceHandle.dev(WorkspaceId.generate(), "ws-test", "net-test", port, 0);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void given_engine_stream_when_runTask_then_full_event_flow_and_exactly_one_usage_event()
            throws InterruptedException {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        RunResult result = adapter.runTask(handle, command("run-1", null,
                new UsageContext("ws-1", Map.of())), events::add);

        assertThat(result.accepted()).isTrue();
        assertThat(result.sessionId()).isEqualTo(SESSION_ID);
        assertThat(result.runId()).isEqualTo("run-1");
        awaitEnd(events);

        // 全事件流：task-start → session-created → part 流（引擎 part 原样进 data）→ task-finish
        assertThat(events).extracting(AgentEvent::type).containsExactly(
                AgentEventTypes.TASK_START, AgentEventTypes.SESSION_CREATED,
                "step-start", "reasoning", "step-finish", "text", "step-finish",
                AgentEventTypes.TASK_FINISH);
        assertThat(events.get(0).payload()).containsEntry("runId", "run-1");
        assertThat(events.get(2).payload().get("data"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "step-start");

        // run 级恰一条 UsageEvent：逐步增量求和后的总量（五档），幂等键 agt-usage-{runId}
        assertThat(usageSink.events).hasSize(1);
        UsageEvent usage = usageSink.events.get(0);
        assertThat(usage.eventId()).isEqualTo("agt-usage-run-1");
        assertThat(usage.tokens()).isEqualTo(new TokenUsage(180, 110, 320, 10, 50));
        assertThat(usage.subject()).isEqualTo("ws-1");
        assertThat(usage.runId()).isEqualTo("run-1");
        assertThat(usage.sessionId()).isEqualTo(SESSION_ID);
        assertThat(usage.provider()).isEqualTo("deepseek");
        assertThat(usage.model()).isEqualTo("deepseek-v4-pro");
        assertThat(usage.engine()).isEqualTo("opencode");
    }

    @Test
    void given_reused_session_when_runTask_then_message_goes_to_existing_session()
            throws InterruptedException {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        int sessionsBefore = serve.sessionCreates.get();

        RunResult result = adapter.runTask(handle, command("run-2", SESSION_ID, null),
                events::add);

        assertThat(result.accepted()).isTrue();
        awaitEnd(events);
        // 复用 = 给既有会话发新消息：不建会话、不发 session-created
        assertThat(serve.sessionCreates.get()).isEqualTo(sessionsBefore);
        assertThat(events).extracting(AgentEvent::type)
                .doesNotContain(AgentEventTypes.SESSION_CREATED);
        assertThat(serve.messagePaths).contains("/session/" + SESSION_ID + "/message");
        // usageContext 为空（调用方不归属）→ 不上报
        assertThat(usageSink.events).isEmpty();
    }

    @Test
    void given_engine_http_failure_when_runTask_then_error_event_and_usage_still_reported_once()
            throws InterruptedException {
        serve.failMessages = true;
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        RunResult result = adapter.runTask(handle, command("run-3", null,
                new UsageContext("ws-1", Map.of())), events::add);

        assertThat(result.accepted()).isTrue();
        awaitEnd(events);
        assertThat(events).extracting(AgentEvent::type).contains(AgentEventTypes.ERROR);
        // run 结束（含失败路径）仍恰一条——零用量是实测值，不是缺报
        assertThat(usageSink.events).hasSize(1);
        assertThat(usageSink.events.get(0).tokens()).isEqualTo(TokenUsage.ZERO);
    }

    @Test
    void given_bootstrap_failure_when_runTask_then_rejected_with_error_event() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        OpenCodeAdapter broken = new OpenCodeAdapter(new PreloadedBootstrap(null, null),
                new AgentModelConfig("deepseek", "deepseek-v4-pro"),
                usageSink, 30, Clock.systemUTC());

        RunResult result = broken.runTask(handle, command("run-4", null,
                new UsageContext("ws-1", Map.of())), events::add);

        assertThat(result.accepted()).isFalse();
        assertThat(result.sessionId()).isNull();
        assertThat(events).extracting(AgentEvent::type)
                .containsExactly(AgentEventTypes.TASK_START, AgentEventTypes.ERROR);
        // 未接单的 run 不上报（无会话无消耗）
        assertThat(usageSink.events).isEmpty();
    }

    @Test
    void given_pending_questions_when_pendingQuestions_then_filtered_by_session() {
        serve.questionVisible = true;
        List<Map<String, Object>> questions = adapter.pendingQuestions(handle, SESSION_ID);

        assertThat(questions).hasSize(1);
        assertThat(questions.get(0)).containsEntry("id", "que_1");
    }

    @Test
    void given_answers_when_replyQuestions_then_engine_body_carries_labels() {
        adapter.replyQuestions(handle, SESSION_ID, "que_1", List.of(List.of("选项A", "自定义输入")));

        assertThat(serve.lastReplyBody).isNotNull();
        assertThat(serve.lastReplyBody.path("answers").get(0).get(0).asText()).isEqualTo("选项A");
    }

    @Test
    void given_approve_when_replyPermission_then_once_response() {
        adapter.replyPermission(handle, SESSION_ID, "perm_1", true);
        assertThat(serve.lastPermissionBody.path("response").asText()).isEqualTo("once");

        adapter.replyPermission(handle, SESSION_ID, "perm_1", false);
        assertThat(serve.lastPermissionBody.path("response").asText()).isEqualTo("reject");
    }

    @Test
    void given_running_serve_when_health_then_true() {
        assertThat(adapter.health(handle)).isTrue();
    }

    @Test
    void given_abort_when_called_then_session_abort_posted() {
        assertThat(adapter.abort(handle, SESSION_ID)).isTrue();

        assertThat(serve.abortCalls).containsExactly(SESSION_ID);
    }

    @Test
    void given_pending_question_when_run_in_flight_then_wait_raised_question_emitted()
            throws InterruptedException {
        serve.questionVisible = true;
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        adapter.runTask(handle, command("run-q", null, null), events::add);

        AgentEvent raised = awaitEvent(events, AgentEventTypes.WAIT_RAISED);
        // 问答发现：引擎 question 载荷原样进 data，中性短文本已提取
        assertThat(raised.payload())
                .containsEntry("runId", "run-q")
                .containsEntry("sessionId", SESSION_ID)
                .containsEntry("kind", "QUESTION")
                .containsEntry("engineRef", "que_1")
                .containsEntry("summary", "用哪个框架?");
        assertThat(raised.payload().get("data"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("id", "que_1");
    }

    @Test
    void given_permission_pending_off_bus_when_run_in_flight_then_polling_detects_and_approve_resumes()
            throws InterruptedException {
        // 票 #35 复现场景：总线静默（permission.updated 帧不达 watcher）——权限检出
        // 全靠 GET /permission 轮询兜底；message 阻塞等批复（引擎真实形态）
        serve.permissionBlocksMessage = true;
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        adapter.runTask(handle, command("run-pp", null, null), events::add);

        AgentEvent raised = awaitEvent(events, AgentEventTypes.WAIT_RAISED);
        // 轮询检出：权限载荷原样进 data，title 为摘要
        assertThat(raised.payload())
                .containsEntry("kind", "PERMISSION")
                .containsEntry("engineRef", "per_env_1")
                .containsEntry("summary", "读取 /workspace/.env");
        assertThat(raised.payload().get("data"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("sessionID", SESSION_ID);
        // 批准送达引擎 → message 解阻塞 → run 正常续跑收口
        adapter.replyPermission(handle, SESSION_ID, "per_env_1", true);
        awaitEnd(events);
        assertThat(serve.lastPermissionBody.path("response").asText()).isEqualTo("once");
    }

    @Test
    void given_permission_on_bus_when_run_in_flight_then_wait_raised_permission_emitted()
            throws InterruptedException {
        serve.permissionOnBus = true;
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        adapter.runTask(handle, command("run-p", null, null), events::add);

        AgentEvent raised = awaitEvent(events, AgentEventTypes.WAIT_RAISED);
        // 权限发现：总线 permission.updated 的 properties 即引擎载荷，title 为摘要
        assertThat(raised.payload())
                .containsEntry("kind", "PERMISSION")
                .containsEntry("engineRef", "per_9")
                .containsEntry("summary", "执行 npm install");
        assertThat(raised.payload().get("data"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("sessionID", SESSION_ID);
        // run 结束后通道收口：terminal 事件仍在（watcher 不干扰主流程）
        awaitEnd(events);
    }

    // ---------- 测试替身 ----------

    /** 等待某类事件抵达（watcher 线程异步上报的同步缝）。 */
    private AgentEvent awaitEvent(List<AgentEvent> events, String type)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            for (AgentEvent event : events) {
                if (event.type().equals(type)) {
                    return event;
                }
            }
            Thread.sleep(50);
        }
        assertThat(events).extracting(AgentEvent::type).contains(type);
        throw new IllegalStateException("unreachable");
    }

    private AgentTaskCommand command(String runId, String sessionId, UsageContext usageContext) {
        return new AgentTaskCommand(runId, "写个落地页", "你是开发工程师", null,
                sessionId, usageContext);
    }

    private void awaitEnd(List<AgentEvent> events) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && events.stream().noneMatch(e -> e.type().equals(AgentEventTypes.TASK_FINISH)
                || e.type().equals(AgentEventTypes.ERROR))) {
            Thread.sleep(50);
        }
        assertThat(events).extracting(AgentEvent::type)
                .containsAnyOf(AgentEventTypes.TASK_FINISH, AgentEventTypes.ERROR);
    }

    /** serve 引导缝替身：直指本地假 serve（null = 引导失败路径）。 */
    private static final class PreloadedBootstrap extends OpenCodeServeBootstrap {
        private final OpenCodeServeBootstrap.ServeEndpoint endpoint;

        PreloadedBootstrap(String baseUrl, String password) {
            super(null, null);
            this.endpoint = baseUrl == null ? null
                    : new OpenCodeServeBootstrap.ServeEndpoint(baseUrl, password);
        }

        @Override
        public ServeEndpoint ensureRunning(WorkspaceHandle handle) {
            if (endpoint == null) {
                throw new IllegalStateException("opencode serve 拉起失败（测试注入）");
            }
            return endpoint;
        }

        @Override
        public boolean isRunning(WorkspaceHandle handle) {
            return endpoint != null;
        }
    }

    /** 本地假 serve：opencode 1.18 已核对端点的最小行为复刻（含事件总线 /event）。 */
    private static final class StubServe implements com.sun.net.httpserver.HttpHandler {

        private final ObjectMapper mapper = new ObjectMapper();
        final AtomicInteger sessionCreates = new AtomicInteger();
        final List<String> messagePaths = new CopyOnWriteArrayList<>();
        final List<String> abortCalls = new CopyOnWriteArrayList<>();
        volatile boolean failMessages;
        volatile JsonNode lastReplyBody;
        volatile JsonNode lastPermissionBody;
        /** 问答是否对 watcher 可见（默认隐藏——不影响既有严格事件序断言）。 */
        volatile boolean questionVisible;
        /** 是否在事件总线上推 permission.updated（权限发现通道的注入点）。 */
        volatile boolean permissionOnBus;
        /** 权限帧已写出（message 响应等它——保证 watcher 先于 run 结束检出）。 */
        volatile boolean permissionDelivered;
        /** 权限挂起阻塞 message（引擎真实形态：agent 等批复）；GET /permission 列出挂起。 */
        volatile boolean permissionBlocksMessage;
        /** 已批复的权限决策（once/reject；message 阻塞的放行信号）。 */
        volatile String permissionDecision;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (exchange.getRequestHeaders().getFirst("Authorization") == null
                        || !exchange.getRequestHeaders().getFirst("Authorization").startsWith("Basic ")) {
                    respond(exchange, 401, "{}");
                    return;
                }
                if (path.equals("/global/health")) {
                    respond(exchange, 200, "{\"status\":\"ok\"}");
                } else if (path.equals("/session") ) {
                    sessionCreates.incrementAndGet();
                    respond(exchange, 200, "{\"id\":\"" + SESSION_ID + "\"}");
                } else if (path.equals("/session/" + SESSION_ID + "/message")) {
                    messagePaths.add(path);
                    drain(exchange);
                    if (permissionOnBus) {
                        // message 同步阻塞直到权限帧已上总线（复刻「agent 提问时阻塞」，
                        // 兼作 watcher 检出的确定性时序）
                        while (!permissionDelivered) {
                            Thread.sleep(20);
                        }
                    }
                    if (permissionBlocksMessage) {
                        // agent 撞权限 ask：挂起等批复（平台 settle 或直批引擎放行）
                        awaitPermissionDecision();
                    }
                    if (failMessages) {
                        respond(exchange, 500, "{\"name\":\"UnknownError\",\"data\":{\"message\":\"boom\"}}");
                        return;
                    }
                    respond(exchange, 200, """
                            {"info":{"finish":"end"},"parts":[
                              {"type":"step-start"},
                              {"type":"reasoning","text":"思考"},
                              {"type":"step-finish","tokens":{"input":100,"output":50,"cache":{"read":200,"write":10},"reasoning":30}},
                              {"type":"text","text":"最终文本"},
                              {"type":"step-finish","tokens":{"input":80,"output":60,"cache":{"read":120,"write":0},"reasoning":20}}
                            ]}
                            """);
                } else if (path.equals("/question") ) {
                    if (!questionVisible) {
                        respond(exchange, 200, "[]");
                        return;
                    }
                    respond(exchange, 200, """
                            [{"id":"que_1","sessionID":"%s","questions":[{"question":"用哪个框架?"}]},
                             {"id":"que_2","sessionID":"ses_other","questions":[]}]
                            """.formatted(SESSION_ID));
                } else if (path.startsWith("/question/") && path.endsWith("/reply")) {
                    lastReplyBody = mapper.readTree(drain(exchange));
                    respond(exchange, 200, "{}");
                } else if (path.equals("/permission")) {
                    // 1.18 列表端点：挂起中的权限（批复后即消失）
                    if (!permissionBlocksMessage || permissionDecision != null) {
                        respond(exchange, 200, "[]");
                        return;
                    }
                    respond(exchange, 200, """
                            [{"id":"per_env_1","sessionID":"%s","messageID":"msg_1",
                              "type":"read","title":"读取 /workspace/.env",
                              "metadata":{},"time":{"created":1}}]
                            """.formatted(SESSION_ID).replace("\n", ""));
                } else if (path.contains("/permissions/")) {
                    lastPermissionBody = mapper.readTree(drain(exchange));
                    permissionDecision = lastPermissionBody.path("response").asText();
                    respond(exchange, 200, "{}");
                } else if (path.equals("/session/" + SESSION_ID + "/abort")) {
                    abortCalls.add(SESSION_ID);
                    drain(exchange);
                    respond(exchange, 200, "true");
                } else if (path.equals("/event")) {
                    streamEventBus(exchange);
                } else {
                    respond(exchange, 404, "{}");
                }
            } catch (Exception e) {
                respond(exchange, 500, "{}");
            }
        }

        /** 权限阻塞的放行等待（带兜底时限：批复丢失也不至于挂死测试进程）。 */
        private void awaitPermissionDecision() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10_000;
            while (permissionDecision == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        }

        /** /event SSE：首帧 server.connected，按需再推一帧 permission.updated。 */
        private void streamEventBus(HttpExchange exchange) throws Exception {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                writeFrame(out, "{\"id\":\"e0\",\"type\":\"server.connected\",\"properties\":{}}");
                if (permissionOnBus) {
                    // 1.18 wire 格式：{id, type, properties}（properties = Permission 全量）
                    writeFrame(out, """
                            {"id":"e1","type":"permission.updated","properties":{
                              "id":"per_9","sessionID":"%s","messageID":"msg_1",
                              "type":"bash","title":"执行 npm install",
                              "metadata":{},"time":{"created":1}}
                            }
                            """.formatted(SESSION_ID).replace("\n", ""));
                    permissionDelivered = true;
                }
                // 保持连接直到对端断开（run 结束 watcher 停止即断）或测试收尾
                while (!exchange.getRequestMethod().isEmpty()) {
                    Thread.sleep(100);
                }
            } catch (IOException | InterruptedException e) {
                // 对端断开/服务停止：连接自然收尾
            }
        }

        private void writeFrame(OutputStream out, String json) throws IOException {
            out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private String drain(HttpExchange exchange) throws IOException {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static final class RecordingUsageSink
            implements com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink {

        final List<UsageEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void report(UsageEvent event) {
            events.add(event);
        }
    }
}
