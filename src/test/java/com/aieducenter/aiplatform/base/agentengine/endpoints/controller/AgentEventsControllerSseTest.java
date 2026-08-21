package com.aieducenter.aiplatform.base.agentengine.endpoints.controller;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import cn.hutool.core.collection.CollUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.MediaType;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseChannelHub;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * agent 流通道真实验收（issue #20）：真实 HTTP/SSE 线格式——统一信封
 * {type,payload,ts}、id {runId}:{seq}、?runId= 过滤、底座直发事件带 workspaceId。
 * 窄上下文（同 EventsControllerSseTest 形态）不依赖本机 PG：通道两件套以显式
 * @Bean 装配（agentengine 全包扫描会牵连 JPA 仓储，不进本测试上下文）。
 */
@SpringBootTest(classes = AgentEventsControllerSseTest.NarrowApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
                + "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                + "com.cartisan.data.jpa.config.CartisanDataJpaAutoConfiguration"
})
class AgentEventsControllerSseTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private AgentStreamAppService appService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BffSessionStore sessionStore;

    private static final String TEST_SESSION_ID = "agent-sse-test-session";

    private final List<SseClient> clients = CollUtil.newArrayList();

    @AfterEach
    void tearDown() {
        clients.forEach(SseClient::close);
    }

    private SseClient connect(String query) throws Exception {
        sessionStore.put(TEST_SESSION_ID, new BffSession(1L, "sse-test", "idt", "at", "rt",
                Instant.now().plusSeconds(600)));
        SseClient client = SseClient.connect(port, query,
                AuthCookies.SESSION_COOKIE_NAME + "=" + TEST_SESSION_ID);
        clients.add(client);
        return client;
    }

    @Test
    void given_connected_when_subscribe_then_initial_ping_flushed() throws Exception {
        SseClient client = connect("");

        assertThat(client.pollLine()).isEqualTo(":ping");
    }

    @Test
    void given_publish_when_subscribed_then_envelope_id_and_workspace_field_on_the_wire()
            throws Exception {
        SseClient client = connect("");

        appService.publish("task-start", Map.of(
                "runId", "run-wire", "prompt", "写个落地页",
                "model", "deepseek-v4-pro", "engine", "opencode",
                "workspaceId", "4242"));

        assertThat(client.nextNonCommentLine()).isEqualTo("id:run-wire:1");
        assertThat(client.nextNonCommentLine()).isEqualTo("event:event");
        String dataLine = client.nextNonCommentLine();
        JsonNode envelope = objectMapper.readTree(dataLine.substring("data:".length()));

        assertThat(envelope.get("type").asText()).isEqualTo("task-start");
        assertThat(envelope.get("payload").get("runId").asText()).isEqualTo("run-wire");
        // 底座任务端点直发事件带 workspaceId（projectId 自片5 业务桥接注入）
        assertThat(envelope.get("payload").get("workspaceId").asText()).isEqualTo("4242");
        assertThat(envelope.get("payload").has("type")).isFalse(); // payload 内禁 type 键名
        Instant.parse(envelope.get("ts").asText()); // ISO-8601 可解析（非法即抛）
    }

    @Test
    void given_run_filter_when_publish_other_run_then_only_matching_received() throws Exception {
        SseClient client = connect("?runId=run-1");

        appService.publish("task-start", Map.of("runId", "run-2", "prompt", "x"));
        appService.publish("task-finish", Map.of("runId", "run-1", "finish", "end"));

        assertThat(client.nextNonCommentLine()).isEqualTo("id:run-1:1");
        client.skipEventBody();
        // run-2 事件被过滤挡掉：5s 内无新帧（心跳除外）
        assertThat(client.nextNonCommentLine(1500)).isNull();
    }

    @Test
    void given_swagger_group_when_fetch_api_docs_then_description_embeds_roster() {
        String apiDocs = restTemplate.getForObject("/v3/api-docs/agentengine", String.class);

        assertThat(apiDocs).contains("/api/agent-events");
        assertThat(apiDocs).contains("SSE事件清单");   // 名册正本指引
        assertThat(apiDocs).contains("task-start");   // 名册精简表
        assertThat(apiDocs).contains("runId");
    }

    /**
     * 最小 SSE 消费端：JDK HttpClient 收 InputStream，后台线程逐行入队。
     */
    private static final class SseClient implements AutoCloseable {

        private final HttpClient httpClient;
        private final HttpResponse<InputStream> response;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final Thread readerThread;
        private volatile boolean closed;

        private SseClient(HttpClient httpClient, HttpResponse<InputStream> response) {
            this.httpClient = httpClient;
            this.response = response;
            this.readerThread = new Thread(this::drain, "agent-sse-test-reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        static SseClient connect(int port, String query, String cookie) throws Exception {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/agent-events" + query))
                    .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .header("Cookie", cookie)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("SSE 连接失败：HTTP " + response.statusCode());
            }
            return new SseClient(httpClient, response);
        }

        /** 取下一行（含注释行与空行），超时返回 null。 */
        String pollLine() throws InterruptedException {
            return lines.poll(POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        String pollLine(long timeoutMillis) throws InterruptedException {
            return lines.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        /** 取下一条非注释、非空行；超时返回 null。 */
        String nextNonCommentLine(long timeoutMillis) throws InterruptedException {
            String line = pollLine(timeoutMillis);
            while (line != null && (line.isEmpty() || line.startsWith(":"))) {
                line = pollLine(timeoutMillis);
            }
            return line;
        }

        String nextNonCommentLine() throws InterruptedException {
            String line = nextNonCommentLine(POLL_TIMEOUT.toMillis());
            if (line == null) {
                throw new AssertionError("5s 内未收到下一行 SSE 输出");
            }
            return line;
        }

        /** 跳过一帧剩余的 event:/data: 行（已读过 id: 行之后）。 */
        void skipEventBody() throws InterruptedException {
            assertThat(nextNonCommentLine()).isEqualTo("event:event");
            assertThat(nextNonCommentLine()).startsWith("data:");
        }

        private void drain() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Exception ignored) {
                // 连接关闭属正常路径
            }
        }

        @Override
        public void close() {
            closed = true;
            readerThread.interrupt();
            try {
                response.body().close();
            } catch (Exception ignored) {
                // 已关闭
            }
            httpClient.close();
        }
    }

    /**
     * 窄上下文入口：eventhub 内核 + 共享 web/config + identity 会话存储（/api/**
     * 在拦截面内，测试自种会话）；agent 流通道两件套显式装配（不扫 agentengine
     * 全包，避免 JPA 仓储牵连本测试）。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
            "com.aieducenter.aiplatform.config",
            "com.aieducenter.aiplatform.web",
            "com.aieducenter.aiplatform.base.eventhub",
            "com.aieducenter.aiplatform.business.identity.infrastructure.session"})
    static class NarrowApp {

        @Bean
        AgentStreamAppService agentStreamAppService(SseChannelHub hub) {
            return new AgentStreamAppService(hub);
        }

        @Bean
        AgentEventsController agentEventsController(AgentStreamAppService appService) {
            return new AgentEventsController(appService);
        }
    }
}
