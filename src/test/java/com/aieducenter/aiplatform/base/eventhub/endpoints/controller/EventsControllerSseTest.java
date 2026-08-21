package com.aieducenter.aiplatform.base.eventhub.endpoints.controller;

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
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.MediaType;

import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;

import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 通知通道真实验收（issue #14）：真实 HTTP/SSE 线格式——心跳注释行、统一信封
 * {type,payload,ts}、id {projectId}:{seq}、?projectId= 过滤、fire-and-forget、
 * swagger 端点描述嵌名册指引。窄上下文（{@link NarrowApp} 只扫 eventhub + 共享
 * web/config，排除数据面 autoconfig）不依赖本机 PG——业务 BC 落码（如依赖 JPA
 * 仓储的 workspace）不被本上下文牵连，springdoc 取分组文档也不会触达其 controller。
 */
@SpringBootTest(classes = EventsControllerSseTest.NarrowApp.class,
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
class EventsControllerSseTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private PlatformNotificationAppService appService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** /api/** 拦截面（A2）要求会话——窄上下文带上 identity 会话存储，测试自种一个 */
    @Autowired
    private BffSessionStore sessionStore;

    private static final String TEST_SESSION_ID = "sse-test-session";

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
    void given_connected_when_subscribe_then_initial_ping_and_headers_flushed() throws Exception {
        SseClient client = connect("");

        assertThat(client.contentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        // 连接建立即刻收到 :ping（响应头冲刷 + 即时存活信号），不进前端 listener
        assertThat(client.pollLine()).isEqualTo(":ping");
    }

    @Test
    void given_publish_when_subscribed_then_contract_envelope_and_id_on_the_wire() throws Exception {
        SseClient client = connect("");

        appService.publish("workspace-created", Map.of(
                "projectId", "p-wire",
                "projectName", "官网 demo",
                "container", "aiplatform-dev-p-wire",
                "projectType", "WEBSITE",
                "engine", "opencode"));

        // 线格式：id → event → data（Spring 按设置顺序写出）
        assertThat(client.nextNonCommentLine()).isEqualTo("id:p-wire:1");
        assertThat(client.nextNonCommentLine()).isEqualTo("event:event");

        String dataLine = client.nextNonCommentLine();
        assertThat(dataLine).startsWith("data:");
        JsonNode envelope = objectMapper.readTree(dataLine.substring("data:".length()));

        assertThat(envelope.get("type").asText()).isEqualTo("workspace-created");
        assertThat(envelope.get("payload").get("projectId").asText()).isEqualTo("p-wire");
        assertThat(envelope.get("payload").get("engine").asText()).isEqualTo("opencode");
        assertThat(envelope.get("payload").has("type")).isFalse(); // payload 内禁 type 键名
        String ts = envelope.get("ts").asText();
        assertThat(ts).isNotBlank();
        Instant.parse(ts); // ISO-8601 可解析（非法即抛）

        // 同流第二事件：seq 单调递增
        appService.publish("stage-changed",
                Map.of("projectId", "p-wire", "stage", "DEV", "stageLabel", "开发"));
        assertThat(client.nextNonCommentLine()).isEqualTo("id:p-wire:2");
    }

    @Test
    void given_project_filter_when_publish_other_project_then_only_matching_received() throws Exception {
        SseClient client = connect("?projectId=p-filter");

        appService.publish("stage-changed", Map.of("projectId", "p-other", "stage", "DEV"));
        appService.publish("preview-ready",
                Map.of("projectId", "p-filter", "url", "http://localhost:30080"));

        // 下一帧即订阅项目的事件（p-other 被过滤挡掉），id 取订阅项目流
        assertThat(client.nextNonCommentLine()).isEqualTo("id:p-filter:1");
        assertThat(client.nextNonCommentLine()).isEqualTo("event:event");
        String dataLine = client.nextNonCommentLine();
        JsonNode envelope = objectMapper.readTree(dataLine.substring("data:".length()));
        assertThat(envelope.get("type").asText()).isEqualTo("preview-ready");
        assertThat(envelope.get("payload").get("projectId").asText()).isEqualTo("p-filter");
    }

    @Test
    void given_subscriber_disconnected_when_publish_then_caller_and_other_subscribers_unaffected() throws Exception {
        // fire-and-forget（真实断连）：坏连接不影响调用方，健康订阅照常收事件
        SseClient doomed = connect("");
        SseClient healthy = connect("");

        doomed.close(); // 模拟客户端断连（连接主动掐断，服务端下一次发送才发现）
        clients.remove(doomed);

        assertThatCode(() -> {
            appService.publish("workspace-destroyed", Map.of("projectId", "p-ff"));
            Thread.sleep(200); // 留给服务端发现断连的时间
            appService.publish("workspace-destroyed", Map.of("projectId", "p-ff"));
        }).doesNotThrowAnyException();

        assertThat(healthy.nextNonCommentLine()).isEqualTo("id:p-ff:1");
        healthy.skipEventBody(); // event:event + data:...
        assertThat(healthy.nextNonCommentLine()).isEqualTo("id:p-ff:2");
    }

    @Test
    void given_swagger_group_when_fetch_api_docs_then_description_embeds_roster() {
        String apiDocs = restTemplate.getForObject("/v3/api-docs/eventhub", String.class);

        assertThat(apiDocs).contains("/api/events");
        assertThat(apiDocs).contains("SSE事件清单");   // 名册正本指引
        assertThat(apiDocs).contains("workspace-created"); // 名册精简表
        assertThat(apiDocs).contains("projectId");
    }

    /**
     * 最小 SSE 消费端：JDK HttpClient 收 InputStream（自动解 chunked），后台线程逐行入队。
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
            this.readerThread = new Thread(this::drain, "sse-test-reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        static SseClient connect(int port, String query, String cookie) throws Exception {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/events" + query))
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

        String contentType() {
            return response.headers().firstValue("Content-Type").orElse("");
        }

        /** 取下一行（含注释行与空行），超时返回 null。 */
        String pollLine() throws InterruptedException {
            return lines.poll(POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** 取下一条非注释、非空行。 */
        String nextNonCommentLine() throws InterruptedException {
            String line = pollLine();
            while (line != null && (line.isEmpty() || line.startsWith(":"))) {
                line = pollLine();
            }
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
     * 窄上下文入口：只扫 eventhub 本包 + 共享 web/config（全局异常处理、SpringDoc
     * 分组、swagger 重定向、/api/** 鉴权拦截）+ identity 会话存储（A2 起 /api/events
     * 在拦截面内，测试自种会话），不扫业务/其他 BC——本测试只验 SSE 通道，不依赖数据面。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
            "com.aieducenter.aiplatform.config",
            "com.aieducenter.aiplatform.web",
            "com.aieducenter.aiplatform.base.eventhub",
            "com.aieducenter.aiplatform.business.identity.infrastructure.session"})
    static class NarrowApp {
    }
}
