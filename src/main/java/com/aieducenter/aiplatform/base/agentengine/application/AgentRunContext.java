package com.aieducenter.aiplatform.base.agentengine.application;

import java.util.Map;

import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.agentengine.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.domain.model.EventEnvelope;

/**
 * 编排下发上下文（片5 business.project 接管业务编排的下发缝，A1 §2.3/§2.4）：
 * runId 由任务端点（业务编排层）生成、计量归属由业务侧组装（subject=projectId +
 * 业务 dims）、流关联字段随帧透传（如 {@code projectId}——底座不解释，只注入
 * agent 流 payload）。
 *
 * <p>全部可空——缺省回退底座中性兜底（runId 生成 / subject=workspaceId /
 * 不注入关联字段，即底座任务端点的既有行为）。带关联字段时 {@code wait-raised}
 * 落库后照常透传（发射归编排层的口径不变：底座只对带关联的调用方补发，
 * 中性端点行为不变）。</p>
 *
 * @param runId            业务侧生成的运行标识（可空 → 底座生成）
 * @param usageContext     计量归属（可空 → subject=workspaceId 兜底）
 * @param streamCorrelation 流关联字段（可空 → 不注入；禁 {@code type} 键，信封契约）
 */
public record AgentRunContext(String runId, UsageContext usageContext,
                              Map<String, Object> streamCorrelation) {

    public AgentRunContext {
        streamCorrelation = streamCorrelation == null ? Map.of() : Map.copyOf(streamCorrelation);
        if (streamCorrelation.containsKey(EventEnvelope.TYPE_KEY)) {
            throw new IllegalArgumentException(
                    "streamCorrelation 禁含 " + EventEnvelope.TYPE_KEY + " 键（信封契约）");
        }
    }

    /**
     * runId 生成（任务端点生成，ADR-0001）：TSID 十进制字符串——编排层（片5 /
     * 修复链 / 回填续跑）与底座同构的唯一生成口（SSE id / 库列 / 日志共用形）。
     */
    public static String newRunId() {
        return Long.toString(TsidGenerator.newInstance().generate());
    }
}
