package com.aieducenter.aiplatform.base.metering.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * 用量事件协议（A1 §2.2，研究稿 F4.2 照收）：一次上报 = 一条 run 级 token 记录。
 *
 * <p>字段语义：{@code eventId} 调用方生成的幂等键（重复上报 first-write-wins）；
 * {@code subject} 不透明归属 id（业务层传 projectId，底座不解释）；{@code runId}/
 * {@code sessionId} 运行与会话寻址（可空——非 run 级来源可不带）；{@code provider}/
 * {@code model}/{@code engine} 模型与引擎标识（A6 单价表匹配键）；{@code dims} 业务
 * 维度透传（role/stage/iterationId 等，底座不解释，可空）；{@code tokens}
 * 五档互斥分解（见 {@link TokenUsage}）。run 级一条：step-finish 增量求和是 adapter
 * 内部实现，不进协议。</p>
 */
public record UsageEvent(
        String eventId,
        Instant ts,
        String subject,
        String runId,
        String sessionId,
        String provider,
        String model,
        String engine,
        Map<String, String> dims,
        TokenUsage tokens) {
}
