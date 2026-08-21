package com.aieducenter.aiplatform.base.knowledge.infrastructure.embedding;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fastembed 客户端单测（本地 HttpServer 桩）：请求契约（POST /embed、texts 同序）
 * 与降级契约（非 200 / 连不上 / 响应异常 → 空列表不抛错，B0 蓝图 §2 片3 口径）。
 */
class FastembedEmbeddingClientTest {

    private HttpServer server;
    private FastembedEmbeddingClient client;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new FastembedEmbeddingClient(
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void given_texts_when_embed_then_posts_json_and_parses_vectors() {
        respond(200, "{\"vectors\":[[0.1,0.2,0.3],[-0.4]]}");

        List<float[]> vectors = client.embed(List.of("你好", "世界"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(vectors.get(1)).containsExactly(-0.4f);
        // 请求契约：POST /embed，JSON 体 texts 与入参同序
        assertThat(requests).containsExactly(
                "POST /embed content-type=application/json body={\"texts\":[\"你好\",\"世界\"]}");
    }

    @Test
    void given_empty_texts_when_embed_then_empty_vectors_parsed() {
        respond(200, "{\"vectors\":[]}");

        assertThat(client.embed(List.of())).isEmpty();
    }

    @Test
    void given_non_200_when_embed_then_empty() {
        respond(500, "{\"error\":\"boom\"}");

        assertThat(client.embed(List.of("文本"))).isEmpty();
    }

    @Test
    void given_malformed_body_when_embed_then_empty() {
        respond(200, "not-json");

        assertThat(client.embed(List.of("文本"))).isEmpty();
    }

    @Test
    void given_connection_refused_when_embed_then_empty() {
        // 指向一个确定无监听的端口（先占后放）
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FastembedEmbeddingClient dead = new FastembedEmbeddingClient("http://127.0.0.1:" + port);

        assertThat(dead.embed(List.of("文本"))).isEmpty();
    }

    // ---------- fixture ----------

    private void respond(int status, String body) {
        server.createContext("/embed", exchange -> {
            requests.add(String.format("%s %s content-type=%s body=%s", exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            write(exchange, status, body);
        });
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
