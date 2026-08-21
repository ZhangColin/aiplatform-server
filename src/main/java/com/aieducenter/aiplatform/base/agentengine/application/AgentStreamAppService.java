package com.aieducenter.aiplatform.base.agentengine.application;

import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aieducenter.aiplatform.base.eventhub.infrastructure.sse.SseChannelHub;

/**
 * agent 流通道语义（SSE事件清单·通道二，ADR-0001）：{@code GET /api/agent-events}
 * 的订阅与发射入口，复用 eventhub 的 SSE 传输内核（通道按名泛化，双通道只共用内核）。
 *
 * <ul>
 *   <li>关联字段 {@code runId}：agent 流事件 payload 必带（适配器回调透传时已盖上）、
 *       streamId 同值（事件 id 取 {@code {runId}:{seq}}，补发留缝的 ID 格式）；
 *       {@code workspaceId} 为片2a 底座任务端点直发事件的关联字段，{@code projectId}
 *       自片5 业务编排桥接注入（均为透传字段，过滤位同名先留）</li>
 *   <li>订阅过滤：{@code ?projectId=} / {@code ?runId=} / {@code ?workspaceId=}
 *       （与 payload 字段同名，可叠用 AND；缺省全量，任务进度页「看某个运行才挂」
 *       即 ?runId=）</li>
 *   <li>发射制：runTask 的 sink 桥（{@link AgentTaskAppService}）在适配器回调线程透传</li>
 * </ul>
 *
 * <p>事件 type 名册见 docs/spec/SSE事件清单.md（代码侧引用 {@code AgentEventTypes}
 * 常量，禁止字符串字面量散落）。</p>
 */
@Service
public class AgentStreamAppService {

    /** agent 流通道名（内核按名泛化，与通知通道是两回事）。 */
    public static final String CHANNEL = "agent-stream";

    /** 必带关联字段（事件 id 的 streamId 同值）。 */
    public static final String RUN_FIELD = "runId";

    /** 片2a 底座任务端点直发事件的关联字段（重启/调试寻址；projectId 片5 注入后并存）。 */
    public static final String WORKSPACE_FIELD = "workspaceId";

    /** 业务编排桥接（片5）注入的透传关联字段（底座直发事件不带，过滤位先留）。 */
    public static final String PROJECT_FIELD = "projectId";

    private final SseChannelHub hub;

    public AgentStreamAppService(SseChannelHub hub) {
        this.hub = hub;
    }

    /**
     * 订阅 agent 流。过滤参数可单用可叠用（AND），均为空 = 全量。
     */
    public SseEmitter subscribe(String projectId, String runId, String workspaceId) {
        return hub.subscribe(CHANNEL, payload ->
                matches(payload, PROJECT_FIELD, projectId)
                        && matches(payload, RUN_FIELD, runId)
                        && matches(payload, WORKSPACE_FIELD, workspaceId));
    }

    /**
     * 发射一帧 agent 流事件（fire-and-forget）。payload 必带关联字段 runId——本层
     * fail-fast；payload 内禁 type 键名由信封 {@code EventEnvelope} 构造时统一拒收
     * （内核落地的信封契约）。
     */
    public void publish(String type, Map<String, Object> payload) {
        Object runId = payload.get(RUN_FIELD);
        if (runId == null || runId.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "agent 流 payload 必带关联字段 " + RUN_FIELD + "（SSE事件清单·信封）");
        }
        hub.broadcast(CHANNEL, runId.toString(), type, payload);
    }

    private static boolean matches(Map<String, Object> payload, String field, String expected) {
        return expected == null || expected.isBlank()
                || Objects.equals(String.valueOf(payload.get(field)), expected);
    }
}
