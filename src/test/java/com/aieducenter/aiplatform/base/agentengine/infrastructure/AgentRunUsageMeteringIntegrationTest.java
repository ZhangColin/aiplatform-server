package com.aieducenter.aiplatform.base.agentengine.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.dsh.DshAdapter;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.opencode.OpenCodeAdapter;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.opencode.OpenCodeServeBootstrap;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * run 级用量埋点落库验收（issue #20）：opencode 引擎经真实 Spring 装配
 * （适配器 bean → UsageEventSink 端口 → MeteringAppService → PG）恰一条入
 * `met_usage_events`；dsh headless 无事件。引擎侧以本地 HTTP 假 serve / 环境后端
 * 替身顶替（真容器交互归片2a 手动验收），计量链路全真（B0 §5.2 副作用以真实状态为准）。
 */
@SpringBootTest
class AgentRunUsageMeteringIntegrationTest {

    @Autowired
    private OpenCodeAdapter openCodeAdapter;

    @Autowired
    private DshAdapter dshAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 引导替身：直指本地假 serve（探活即过，拉起路径不触发）。 */
    @MockitoBean
    private OpenCodeServeBootstrap serveBootstrap;

    /** 环境后端替身：dsh headless 的 exec 回放（文件写入类命令缺省成功）。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    private HttpServer server;
    private WorkspaceHandle handle;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", AgentRunUsageMeteringIntegrationTest::respondOk);
        server.start();
        int port = server.getAddress().getPort();
        when(serveBootstrap.ensureRunning(any()))
                .thenReturn(new OpenCodeServeBootstrap.ServeEndpoint(
                        "http://localhost:" + port, "it-password"));
        when(environmentBackend.exec(any(), anyString()))
                .thenAnswer(invocation -> new ExecResult("", "", 0));
        handle = WorkspaceHandle.dev(WorkspaceId.generate(), "ws-it", "net-it", port, 0);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        jdbcTemplate.update("DELETE FROM met_usage_events WHERE run_id LIKE 'it-run-%'");
    }

    @Test
    void given_opencode_run_when_finished_then_exactly_one_usage_row_with_summed_tokens()
            throws InterruptedException {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        openCodeAdapter.runTask(handle,
                new AgentTaskCommand("it-run-oc", "写个落地页", null, null, null,
                        new UsageContext("ws-it", Map.of())),
                events::add);
        awaitEnd(events);
        awaitUsageRow("it-run-oc");

        // 恰一条：run 级汇总（假 serve 两步 step-finish 增量 → 五档求和）真实落库
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT event_id, subject, provider, model, engine, input, output, cache_read, cache_write, reasoning "
                        + "FROM met_usage_events WHERE run_id = 'it-run-oc'");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE run_id = 'it-run-oc'", Integer.class))
                .isEqualTo(1);
        assertThat(row.get("event_id")).isEqualTo("agt-usage-it-run-oc");
        assertThat(row.get("subject")).isEqualTo("ws-it");
        assertThat(row.get("engine")).isEqualTo("opencode");
        assertThat(row.get("provider")).isEqualTo("deepseek");
        assertThat(row.get("model")).isEqualTo("deepseek-v4-pro");
        assertThat(((Number) row.get("input")).longValue()).isEqualTo(180L);
        assertThat(((Number) row.get("output")).longValue()).isEqualTo(110L);
        assertThat(((Number) row.get("cache_read")).longValue()).isEqualTo(320L);
        assertThat(((Number) row.get("cache_write")).longValue()).isEqualTo(10L);
        assertThat(((Number) row.get("reasoning")).longValue()).isEqualTo(50L);
    }

    @Test
    void given_dsh_run_when_finished_then_no_usage_row() throws InterruptedException {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        dshAdapter.runTask(handle,
                new AgentTaskCommand("it-run-dsh", "写个落地页", null, null, null,
                        new UsageContext("ws-it", Map.of())),
                events::add);
        awaitEnd(events);

        // headless 无 usage 可采：无数据不造数（A1 §2.3）——即便调用方已归属
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE run_id = 'it-run-dsh'", Integer.class))
                .isEqualTo(0);
        assertThat(events).extracting(AgentEvent::type).contains(AgentEventTypes.TASK_FINISH);
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

    /** 上报在 task-finish 事件的 finally 内落库——等行可见再断言（事件先到、落库紧随）。 */
    private void awaitUsageRow(String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE run_id = '" + runId + "'",
                Integer.class) == 0) {
            Thread.sleep(50);
        }
    }

    /** 假 serve：全部 200（健康/建会话/消息——消息带两步 step-finish 增量）。 */
    private static void respondOk(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = path.endsWith("/message")
                ? """
                {"info":{"finish":"end"},"parts":[
                  {"type":"step-finish","tokens":{"input":100,"output":50,"cache":{"read":200,"write":10},"reasoning":30}},
                  {"type":"step-finish","tokens":{"input":80,"output":60,"cache":{"read":120,"write":0},"reasoning":20}}
                ]}
                """
                : path.equals("/session") ? "{\"id\":\"ses_it\"}" : "{\"status\":\"ok\"}";
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
