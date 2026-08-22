package com.aieducenter.aiplatform.base.agentengine.application;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentTaskDispatchCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentTaskResponse;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.opencode.OpenCodeServeBootstrap;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 权限等待点全链路验收（票 #35）：opencode 权限挂起 → 平台 waits 检出 → settle
 * 批准/拒绝 → run 续跑/终止。真实 Spring 装配（适配器 watcher → 任务流桥 → 等待点
 * 用例 → PG 落库 → settle 引擎派发），引擎侧以本地假 serve 顶替——总线静默
 * （#35 冒烟复现形态），检出全靠 /permission 轮询；message 阻塞等批复（引擎真实
 * 形态），批复到达即放行返回。
 */
@SpringBootTest
class AgentPermissionWaitFlowIntegrationTest {

    private static final long WORKSPACE_ID = 987654330L;
    private static final String WORKSPACE = Long.toString(WORKSPACE_ID);
    private static final String SESSION_ID = "ses_pwait_1";
    private static final String PERMISSION_ID = "per_it_1";

    @Autowired
    private AgentTaskAppService taskAppService;
    @Autowired
    private AgentWaitAppService waitAppService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 引导替身：直指本地假 serve（探活即过，拉起路径不触发）。 */
    @MockitoBean
    private OpenCodeServeBootstrap serveBootstrap;

    /** 句柄替身：工作区不落 workspace 库表（等待点链路不依赖其真实状态）。 */
    @MockitoBean
    private WorkspaceHandleClient handleClient;

    /** 环境后端替身：与计量 IT 同口径（本链路不触容器）。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    private HttpServer server;
    private PermissionBlockingEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        engine = new PermissionBlockingEngine();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", engine);
        // /event 长连接与阻塞中的 /message 各占一线程——池化防饿死
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        int port = server.getAddress().getPort();
        when(serveBootstrap.ensureRunning(any()))
                .thenReturn(new OpenCodeServeBootstrap.ServeEndpoint(
                        "http://localhost:" + port, "it-pwait-password"));
        WorkspaceHandle handle = WorkspaceHandle.dev(new WorkspaceId(WORKSPACE_ID),
                "ws-pwait", "net-pwait", port, 0);
        when(handleClient.handleOf(WORKSPACE)).thenReturn(handle);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        jdbcTemplate.update("DELETE FROM agt_pending_waits WHERE workspace_id = ?",
                WORKSPACE_ID);
        jdbcTemplate.update("DELETE FROM agt_agent_sessions WHERE workspace_id = ?",
                WORKSPACE_ID);
        jdbcTemplate.update("DELETE FROM met_usage_events WHERE subject = ?", WORKSPACE);
    }

    @Test
    void given_permission_ask_when_settle_approve_then_wait_detected_and_run_resumes()
            throws InterruptedException {
        List<AgentEvent> observed = new CopyOnWriteArrayList<>();

        AgentTaskResponse response = taskAppService.dispatch(WORKSPACE,
                new AgentTaskDispatchCommand("读一下 /workspace/.env", null, null, null,
                        null), null, observed::add);

        assertThat(response.accepted()).isTrue();
        // 验收①：权限挂起平台可检出（总线静默，全靠 /permission 轮询通道）
        WaitPointResponse wait = awaitPendingPermission();
        assertThat(wait.kind()).isEqualTo(WaitKind.PERMISSION);
        assertThat(wait.status()).isEqualTo(WaitStatus.PENDING);
        assertThat(wait.engineRef()).isEqualTo(PERMISSION_ID);
        assertThat(wait.summary()).isEqualTo("读取 /workspace/.env");
        assertThat(wait.sessionId()).isEqualTo(response.sessionId());

        // 验收②：settle 批准 → 引擎收到 once → message 放行 → run 续跑收口
        waitAppService.settle(WORKSPACE, wait.waitId(),
                new WaitSettleCommand(WaitSettleCommand.TYPE_PERMISSION, null, true, null));

        awaitTerminal(observed);
        assertThat(engine.lastPermissionBody.path("response").asText()).isEqualTo("once");
        WaitPointResponse settled = waitAppService.wait(wait.waitId()).orElseThrow();
        assertThat(settled.status()).isEqualTo(WaitStatus.SETTLED);
        assertThat(settled.settleOutcome()).isEqualTo(WaitOutcome.APPROVED);
        assertThat(waitAppService.pendingWaits(WORKSPACE)).isEmpty();
    }

    @Test
    void given_permission_ask_when_settle_reject_then_run_terminated_and_wait_denied()
            throws InterruptedException {
        List<AgentEvent> observed = new CopyOnWriteArrayList<>();

        taskAppService.dispatch(WORKSPACE,
                new AgentTaskDispatchCommand("读一下 /workspace/.env", null, null, null,
                        null), null, observed::add);

        WaitPointResponse wait = awaitPendingPermission();

        // 拒绝：引擎收 reject、run 终止收口（agent 放弃而非续跑）、等待点 DENIED
        waitAppService.settle(WORKSPACE, wait.waitId(),
                new WaitSettleCommand(WaitSettleCommand.TYPE_PERMISSION, null, false, null));

        awaitTerminal(observed);
        assertThat(engine.lastPermissionBody.path("response").asText()).isEqualTo("reject");
        WaitPointResponse settled = waitAppService.wait(wait.waitId()).orElseThrow();
        assertThat(settled.status()).isEqualTo(WaitStatus.SETTLED);
        assertThat(settled.settleOutcome()).isEqualTo(WaitOutcome.DENIED);
        assertThat(waitAppService.pendingWaits(WORKSPACE)).isEmpty();
        // 单次拒绝未达 deny cap：不经 abort 强杀（正常终止路径）
        assertThat(engine.aborts).isEmpty();
    }

    // ---------- 等待缝 ----------

    /** waits 列表轮询到 PERMISSION 等待点（检出面 = pendingWaits，验收①的入口）。 */
    private WaitPointResponse awaitPendingPermission() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (WaitPointResponse wait : waitAppService.pendingWaits(WORKSPACE)) {
                if (wait.kind() == WaitKind.PERMISSION) {
                    return wait;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("权限等待点未被平台检出（15s 内 waits 列表无 PERMISSION）");
    }

    /** run 终态抵达（task-finish / error 均算——批准续跑与拒绝终止两条路都收口）。 */
    private void awaitTerminal(List<AgentEvent> observed) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline
                && observed.stream().noneMatch(e -> e.type().equals(AgentEventTypes.TASK_FINISH)
                || e.type().equals(AgentEventTypes.ERROR))) {
            Thread.sleep(50);
        }
        assertThat(observed).extracting(AgentEvent::type)
                .containsAnyOf(AgentEventTypes.TASK_FINISH, AgentEventTypes.ERROR);
    }

    // ---------- 引擎替身 ----------

    /**
     * 假 opencode serve：agent 撞权限 ask（读 .env）即挂起——message 阻塞等批复，
     * GET /permission 列出挂起，事件总线只发 server.connected（静默，#35 复现形态）。
     */
    private static final class PermissionBlockingEngine implements HttpHandler {

        private final ObjectMapper mapper = new ObjectMapper();
        volatile JsonNode lastPermissionBody;
        /** 批复决策（once/reject）——message 阻塞的放行信号。 */
        volatile String decision;
        final List<String> aborts = new CopyOnWriteArrayList<>();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/session")) {
                    drain(exchange);
                    respond(exchange, 200, "{\"id\":\"" + SESSION_ID + "\"}");
                } else if (path.equals("/session/" + SESSION_ID + "/message")) {
                    drain(exchange);
                    awaitDecision();
                    // 复刻真实引擎节奏：批复后 agent 仍有收尾动作才终态——确定性隔开
                    // 「平台 settle 落库」与「run 终态联动 expireRun」两写者（二者对同一
                    // 行的无锁竞争是既有缺陷，#37 根治，不属 #35 验收面）
                    Thread.sleep(300);
                    // 批准 → 正常完成；拒绝 → agent 放弃收口（run 终止形态）
                    String finish = "once".equals(decision) ? "end" : "permission-denied";
                    respond(exchange, 200, """
                            {"info":{"finish":"%s"},"parts":[{"type":"text","text":"收到批复"}]}
                            """.formatted(finish));
                } else if (path.equals("/permission")) {
                    if (decision == null) {
                        respond(exchange, 200, """
                                [{"id":"%s","sessionID":"%s","messageID":"msg_1",
                                  "type":"read","title":"读取 /workspace/.env",
                                  "metadata":{},"time":{"created":1}}]
                                """.formatted(PERMISSION_ID, SESSION_ID)
                                .replace("\n", ""));
                    } else {
                        respond(exchange, 200, "[]");
                    }
                } else if (path.contains("/permissions/")) {
                    lastPermissionBody = mapper.readTree(drain(exchange));
                    decision = lastPermissionBody.path("response").asText();
                    respond(exchange, 200, "{}");
                } else if (path.equals("/question")) {
                    respond(exchange, 200, "[]");
                } else if (path.equals("/session/" + SESSION_ID + "/abort")) {
                    aborts.add(SESSION_ID);
                    drain(exchange);
                    respond(exchange, 200, "true");
                } else if (path.equals("/event")) {
                    silentBus(exchange);
                } else {
                    respond(exchange, 404, "{}");
                }
            } catch (Exception e) {
                respond(exchange, 500, "{}");
            }
        }

        /** message 阻塞等批复（带兜底时限：批复丢失也不至于挂死测试进程）。 */
        private void awaitDecision() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 20_000;
            while (decision == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        }

        /** 事件总线静默：只发 server.connected（permission.updated 不达 watcher）。 */
        private void silentBus(HttpExchange exchange) throws IOException, InterruptedException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("data: {\"id\":\"e0\",\"type\":\"server.connected\","
                        + "\"properties\":{}}\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                while (true) {
                    Thread.sleep(100); // 保持连接直到对端（watcher stop）断开
                }
            } catch (IOException e) {
                // 对端断开：连接自然收尾
            }
        }

        private String drain(HttpExchange exchange) throws IOException {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }

        private void respond(HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
