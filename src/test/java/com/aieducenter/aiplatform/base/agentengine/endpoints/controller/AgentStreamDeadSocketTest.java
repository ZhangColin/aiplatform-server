package com.aieducenter.aiplatform.base.agentengine.endpoints.controller;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamProperties;
import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseChannelHub;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 死连接回归（真机日志噪音复刻）：真实 HTTP/SSE 订阅后以 RST 掀掉客户端 socket，
 * 撞「服务端 onError 尚未移除订阅」窗口内 broadcast——向死连接写帧失败会被
 * sendOrEvict catch（fire-and-forget 契约），但容器 async 机制把异常 dispatch
 * 回 SSE 端点：全局异常处理器打 ERROR（栈带原始业务帧，极易误读为业务 500）+
 * ApiResponse 写不进 text/event-stream 的二次 WARN。本回归守住两条：
 * 调用方不抛、断连不出 ERROR 噪音（SSE 端点本地 @ExceptionHandler 静默）。
 *
 * <p>与 {@link SseChannelHubTest} 的 RecordingSseSender 用例互补：单测里 emitter
 * 无 handler，complete() 是 no-op——正是「测试全绿、真机一用就错」的那一环。</p>
 */
@SpringBootTest(classes = AgentStreamDeadSocketTest.NarrowApp.class,
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
class AgentStreamDeadSocketTest {

    private static final String TEST_SESSION_ID = "agent-sse-dead-socket-test";

    @LocalServerPort
    private int port;

    @Autowired
    private AgentStreamAppService appService;

    @Autowired
    private BffSessionStore sessionStore;

    @Test
    void given_dead_socket_when_broadcast_in_race_window_then_caller_unaffected()
            throws Exception {
        sessionStore.put(TEST_SESSION_ID, new BffSession(1L, "sse-test", "idt", "at", "rt",
                Instant.now().plusSeconds(600)));

        AtomicBoolean anyPingReceived = new AtomicBoolean(false);
        // 多轮撞窗口：onError 移除是异步的，单次未必命中
        for (int i = 0; i < 8; i++) {
            RawSseClient sse = RawSseClient.connect(port,
                    AuthCookies.SESSION_COOKIE_NAME + "=" + TEST_SESSION_ID);
            try {
                assertThat(sse.awaitPing(Duration.ofSeconds(5))).isTrue();
                anyPingReceived.set(true);
            } finally {
                sse.kill();   // RST 断连：服务端 socket 已死但订阅移除有窗口
            }
            // 撞窗：断连后立即广播（真机 500 的时序）
            String runId = "run-dead-" + i;
            assertThatCode(() -> appService.publish("task-start",
                    Map.of("runId", runId))).doesNotThrowAnyException();
            Thread.sleep(50);   // 给 onError 一点时间清理，进入下一轮
        }
        assertThat(anyPingReceived).isTrue();   // 循环本身有效（订阅确实建立过）
    }

    /**
     * 断连不出 ERROR 噪音：全局异常处理器（GlobalExceptionHandler）与本端点相关的
     * ERROR 日志必须为零——没有本地 @ExceptionHandler 静默时，async error dispatch
     * 每次断连都打一条带业务栈的「Unexpected error」（真机事故现场）。
     */
    @Test
    void given_dead_socket_when_async_error_dispatch_then_no_global_handler_error_noise()
            throws Exception {
        sessionStore.put(TEST_SESSION_ID, new BffSession(1L, "sse-test", "idt", "at", "rt",
                Instant.now().plusSeconds(600)));

        Logger globalHandlerLogger = (Logger) org.slf4j.LoggerFactory
                .getLogger("com.cartisan.web.exception.GlobalExceptionHandler");
        List<ILoggingEvent> captured = new CopyOnWriteArrayList<>();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();   // AppenderBase 未 start 时 doAppend 静默丢弃
        globalHandlerLogger.addAppender(appender);
        try {
            for (int i = 0; i < 8; i++) {
                RawSseClient sse = RawSseClient.connect(port,
                        AuthCookies.SESSION_COOKIE_NAME + "=" + TEST_SESSION_ID);
                assertThat(sse.awaitPing(Duration.ofSeconds(5))).isTrue();
                sse.kill();
                appService.publish("task-start", Map.of("runId", "run-noise-" + i));
                Thread.sleep(50);   // async error dispatch 是异步的，给它时间跑
            }
            captured.addAll(appender.list);
        } finally {
            globalHandlerLogger.detachAppender(appender);
        }
        assertThat(captured.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList())
                .as("断连属 SSE 正常生命周期，全局异常处理器不得落 ERROR")
                .isEmpty();
    }

    /**
     * 真机 500 拓扑复刻：前端双发 + 两条死连接。POST#1 的 broadcast 逐出死订阅 A
     * （complete() 触发 async dispatch，进行中），POST#2 几乎同时 broadcast 遍历到
     * 死订阅 B——并发 sendOrEvict/complete 竞态窗口即穿透现场。
     */
    @Test
    void given_two_dead_sockets_when_concurrent_broadcasts_then_caller_unaffected()
            throws Exception {
        sessionStore.put(TEST_SESSION_ID, new BffSession(1L, "sse-test", "idt", "at", "rt",
                Instant.now().plusSeconds(600)));

        for (int i = 0; i < 8; i++) {
            RawSseClient first = RawSseClient.connect(port,
                    AuthCookies.SESSION_COOKIE_NAME + "=" + TEST_SESSION_ID);
            RawSseClient second = RawSseClient.connect(port,
                    AuthCookies.SESSION_COOKIE_NAME + "=" + TEST_SESSION_ID);
            assertThat(first.awaitPing(Duration.ofSeconds(5))).isTrue();
            assertThat(second.awaitPing(Duration.ofSeconds(5))).isTrue();
            first.kill();
            second.kill();

            // 并发双 publish（真机 POST#1/POST#2 双发时序）——两侧异常都不许有
            AtomicReference<Throwable> racerFailure = new AtomicReference<>();
            String runA = "run-a-" + i;
            String runB = "run-b-" + i;
            assertThatCode(() -> {
                Thread racer = new Thread(() -> {
                    try {
                        appService.publish("task-start", Map.of("runId", runA));
                    } catch (Throwable ex) {
                        racerFailure.set(ex);
                    }
                }, "publish-racer");
                racer.start();
                appService.publish("task-start", Map.of("runId", runB));
                racer.join(2000);
            }).doesNotThrowAnyException();
            assertThat(racerFailure.get()).as("并发 publish 线程亦不得抛").isNull();
            Thread.sleep(50);
        }
    }

    /**
     * 原生 socket 的 SSE 客户端：完全掌控 TCP 层，setSoLinger(0)+close 制造 RST
     * 死连接（JDK HttpClient 不暴露 socket，做不了这个）。
     */
    private static final class RawSseClient {

        private final Socket socket;
        private final BufferedReader reader;

        private RawSseClient(Socket socket, BufferedReader reader) {
            this.socket = socket;
            this.reader = reader;
        }

        static RawSseClient connect(int port, String cookie) throws Exception {
            Socket socket = new Socket("localhost", port);
            socket.setTcpNoDelay(true);
            // BufferedReader 无超时——soTimeout 切片让 awaitPing 的截止时间可兑现
            socket.setSoTimeout(250);
            String request = "GET /api/agent-events HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Accept: text/event-stream\r\n"
                    + "Cookie: " + cookie + "\r\n"
                    + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            return new RawSseClient(socket, reader);
        }

        /** 等到首帧 :ping（订阅已建立并完成响应头冲刷）。 */
        boolean awaitPing(Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                String line;
                try {
                    line = reader.readLine();
                } catch (SocketTimeoutException sliceElapsed) {
                    continue;   // soTimeout 切片到期：回截止时间判断继续等
                }
                if (line == null) {
                    return false;
                }
                if (":ping".equals(line.trim())) {
                    return true;
                }
                // 响应头与空行一路放过
            }
            return false;
        }

        /** RST 断连（非优雅关闭）：服务端视角 socket 已死。 */
        void kill() {
            try {
                socket.setSoLinger(true, 0);
                socket.close();
            } catch (Exception ignored) {
                // 已关
            }
        }
    }

    /**
     * 窄上下文入口（同 AgentEventsControllerSseTest 形态）：不依赖本机 PG。
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
            return new AgentStreamAppService(hub, new AgentStreamProperties());
        }

        @Bean
        AgentEventsController agentEventsController(AgentStreamAppService appService) {
            return new AgentEventsController(appService);
        }
    }
}
