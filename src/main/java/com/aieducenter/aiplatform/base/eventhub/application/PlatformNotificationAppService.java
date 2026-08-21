package com.aieducenter.aiplatform.base.eventhub.application;

import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseChannelHub;

/**
 * 平台通知通道语义（SSE事件清单·通道一，ADR-0001）：{@code GET /api/events} 的
 * 订阅与发射入口。传输内核零业务概念，通道语义集中于此——
 *
 * <ul>
 *   <li>关联字段 {@code projectId}：通知事件 payload 必带、订阅过滤参数同名（缺省全量）</li>
 *   <li>streamId = projectId：事件 id 取 {@code {projectId}:{seq}}</li>
 *   <li>发布制：业务编排层在副作用真实落定后调用 publish（base 区不发 SSE）；
 *       通知永不补发，断线由前端 REST 重查兜底</li>
 * </ul>
 *
 * <p>事件 type 名册见 docs/spec/SSE事件清单.md（新增顶层 type 先进清单再上线）。</p>
 *
 * @since 0.1.0
 */
@Service
public class PlatformNotificationAppService {

    /** 关联字段名：payload 字段与订阅过滤参数同名（ADR-0001 寻址）。 */
    public static final String CORRELATION_FIELD = "projectId";

    /** 通知通道名（内核按名泛化，agent 流通道片2a 另立）。 */
    public static final String CHANNEL = "platform-notification";

    private final SseChannelHub hub;

    public PlatformNotificationAppService(SseChannelHub hub) {
        this.hub = hub;
    }

    /**
     * 订阅平台通知流。projectId 为空 = 全量（开发平台视角）。
     *
     * <p>返回 Spring 的 {@link SseEmitter}（呈现通道的出口句柄，此处即出口 DTO——
     * 编写规范「只返回 DTO/基本类型」对 SSE 通道的唯一偏差，SSE 非 REST）。</p>
     */
    public SseEmitter subscribe(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return hub.subscribe(CHANNEL, payload -> true);
        }
        // 与 streamId 提取（toString）同口径比较，id 行与过滤命中永不劈叉
        return hub.subscribe(CHANNEL, payload ->
                Objects.equals(String.valueOf(payload.get(CORRELATION_FIELD)), projectId));
    }

    /**
     * 发射一帧平台通知（fire-and-forget）。payload 必带关联字段 projectId 且不得
     * 含 type 键——违约即 IllegalArgumentException，属调用方 bug，发射前 fail-fast
     * （发送失败才是运行时故障，只记日志）。
     */
    public void publish(String type, Map<String, Object> payload) {
        Object correlation = payload.get(CORRELATION_FIELD);
        if (correlation == null || correlation.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "平台通知 payload 必带关联字段 " + CORRELATION_FIELD + "（SSE事件清单·信封）");
        }
        hub.broadcast(CHANNEL, correlation.toString(), type, payload);
    }
}
