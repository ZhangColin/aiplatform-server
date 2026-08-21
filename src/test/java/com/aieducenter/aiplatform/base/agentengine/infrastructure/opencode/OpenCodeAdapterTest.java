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

    // ---------- 测试替身 ----------

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

    /** 本地假 serve：opencode 1.18 已核对端点的最小行为复刻。 */
    private static final class StubServe implements com.sun.net.httpserver.HttpHandler {

        private final ObjectMapper mapper = new ObjectMapper();
        final AtomicInteger sessionCreates = new AtomicInteger();
        final List<String> messagePaths = new CopyOnWriteArrayList<>();
        volatile boolean failMessages;
        volatile JsonNode lastReplyBody;
        volatile JsonNode lastPermissionBody;

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
                    respond(exchange, 200, """
                            [{"id":"que_1","sessionID":"%s","questions":[{"question":"用哪个框架?"}]},
                             {"id":"que_2","sessionID":"ses_other","questions":[]}]
                            """.formatted(SESSION_ID));
                } else if (path.startsWith("/question/") && path.endsWith("/reply")) {
                    lastReplyBody = mapper.readTree(drain(exchange));
                    respond(exchange, 200, "{}");
                } else if (path.contains("/permissions/")) {
                    lastPermissionBody = mapper.readTree(drain(exchange));
                    respond(exchange, 200, "{}");
                } else {
                    respond(exchange, 404, "{}");
                }
            } catch (Exception e) {
                respond(exchange, 500, "{}");
            }
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
