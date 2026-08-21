package com.aieducenter.aiplatform.base.knowledge.infrastructure.embedding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.knowledge.domain.port.EmbeddingClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 本机 fastembed embedding 客户端（B0 蓝图 §2 片3：BAAI/bge-small-zh-v1.5，
 * 512 维；服务脚本 {@code scripts/embedding_server.py}，启动方式见
 * docs/guide/本机依赖启动.md）。DeepSeek 无 embeddings 接口 → 本地自托管是
 * Phase A 既定选择（demo 验证过的形态）。
 *
 * <p>降级契约（端口正本）：连不上 / 非 200 / 响应异常一律返回空列表、不抛错，
 * 调用方降级（B0 蓝图 §2 片3「embedding 服务挂了降级不炸」）。请求超时给到
 * 2 分钟：服务冷启动要加载模型（demo 实测口径），连接超时仍为 3 秒——服务
 * 没起时快速失败。</p>
 */
@Component
@Adapter(PortType.CLIENT)
@Slf4j
public class FastembedEmbeddingClient implements EmbeddingClient {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FastembedEmbeddingClient(@Value("${app.embedding.url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/embed"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(2))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of("texts", texts))))
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("embedding 服务响应异常（status={}），按不可用降级", response.statusCode());
                return List.of();
            }
            return readVectors(response.body());
        } catch (Exception e) {
            log.warn("embedding 服务不可用（服务没起？scripts/embedding_server.py，见 docs/guide/本机依赖启动.md）：{}",
                    e.getMessage());
            return List.of();
        }
    }

    private List<float[]> readVectors(String body) throws Exception {
        JsonNode vectors = objectMapper.readTree(body).path("vectors");
        List<float[]> out = new ArrayList<>(vectors.size());
        for (JsonNode vector : vectors) {
            float[] values = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                values[i] = vector.get(i).floatValue();
            }
            out.add(values);
        }
        return out;
    }
}
