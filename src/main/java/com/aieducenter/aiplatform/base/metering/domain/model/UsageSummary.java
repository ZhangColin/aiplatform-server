package com.aieducenter.aiplatform.base.metering.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * 按 subject 聚合的用量读模型（A1 §2.5 查询面：总量 + 分模型/分维度）。
 *
 * <p>度量单位 token——金额（平台成本币种分桶）与按期聚合（{@code dims.iterationId}）
 * 由 A6 扩展（UsageSummary 加 cost/unpriced，票 #29）。分维度 = 事件 dims 内每个
 * (key, value) 各自聚合（role=DEV / stage=BA 各成一桶，无维度的事件不参与）。
 * subject 无任何事件时返回全零 total 与空列表，不是错误。</p>
 */
public record UsageSummary(
        String subject,
        Instant from,
        Instant to,
        TokenUsage total,
        List<ModelUsage> byModel,
        List<DimUsage> byDims) {

    public UsageSummary {
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
        byDims = byDims == null ? List.of() : List.copyOf(byDims);
    }

    /**
     * 分模型聚合项（provider + model 为 A6 单价表匹配键）。
     */
    public record ModelUsage(String provider, String model, TokenUsage tokens) {
    }

    /**
     * 分维度聚合项（dimKey = role/stage 等，dimValue = 该维度的取值）。
     */
    public record DimUsage(String dimKey, String dimValue, TokenUsage tokens) {
    }
}
