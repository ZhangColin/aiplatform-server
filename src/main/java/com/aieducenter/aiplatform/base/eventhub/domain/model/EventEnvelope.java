package com.aieducenter.aiplatform.base.eventhub.domain.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 统一信封（两通道共用）：{@code data = {"type":..., "payload":{...}, "ts":...}}。
 *
 * <p>信封契约（docs/spec/SSE事件清单.md · ADR-0001）：payload 恒为对象且必带关联字段
 * （关联字段的必带校验属通道语义，在应用层做）；payload 内禁用 {@code type} 键名——
 * 违约即 IllegalArgumentException，属调用方 bug，发射前 fail-fast，不是运行时故障。</p>
 *
 * <p>纯值对象，零框架依赖（传输内核信封与 id 分配的领域部分）。</p>
 *
 * @since 0.1.0
 */
public record EventEnvelope(String type, Map<String, Object> payload, Instant ts) {

    /** payload 内禁用的键名（信封外层字段，嵌套会与前端的 type 分发冲突）。 */
    public static final String TYPE_KEY = "type";

    public EventEnvelope {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("SSE 事件 type 不能为空");
        }
        if (payload == null) {
            throw new IllegalArgumentException("SSE 事件 payload 必须是对象（恒为对象，可为空对象）");
        }
        if (payload.containsKey(TYPE_KEY)) {
            throw new IllegalArgumentException("SSE 事件 payload 内禁用 " + TYPE_KEY + " 键名");
        }
        if (ts == null) {
            throw new IllegalArgumentException("SSE 事件 ts 不能为空");
        }
        // 快照：容忍 null 值字段，保持调用方传入的字段顺序
        payload = new LinkedHashMap<>(payload);
    }
}
