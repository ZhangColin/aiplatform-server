package com.aieducenter.aiplatform.base.metering.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;

/**
 * 按 subject 聚合的用量读模型（A1 §2.5 查询面：总量 + 分模型/分维度；A6 §2/§3
 * 扩展：平台成本 + 未配价标注）。
 *
 * <p>{@code cost} = 平台成本（零商业概念：token × 事件时点生效单价的机械乘法，
 * 无加价/售价），<b>按币种分桶不折算</b>（键 = ISO 4217 币种）；{@code unpriced}
 * = 有 token 用量但事件时点无生效单价行的 (provider, model, 档位) 集合——其分量
 * 不进 cost（<b>不伪装 0</b>），前端据此示「未配价」，查询不阻断。分维度 =
 * 事件 dims 内每个 (key, value) 各自聚合（role=DEV / stage=BA / iterationId=7
 * 各成一桶，无维度的事件不参与；按期聚合 = 业务层过滤 iterationId 桶，A6 §3
 * dims 透传缝）。subject 无任何事件时返回全零 total、空 cost 与空列表，不是错误。</p>
 */
public record UsageSummary(
        String subject,
        Instant from,
        Instant to,
        TokenUsage total,
        Map<Currency, BigDecimal> cost,
        List<UnpricedUsage> unpriced,
        List<ModelUsage> byModel,
        List<DimUsage> byDims) {

    public UsageSummary {
        // 保序拷贝：cost 键序 = 聚合 SQL 的币种码序（ORDER BY currency）
        cost = cost == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(cost));
        unpriced = unpriced == null ? List.of() : List.copyOf(unpriced);
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
        byDims = byDims == null ? List.of() : List.copyOf(byDims);
    }

    /**
     * 分模型聚合项（provider + model 为单价表匹配键）。
     */
    public record ModelUsage(String provider, String model, TokenUsage tokens) {
    }

    /**
     * 分维度聚合项（dimKey = role/stage/iterationId 等，dimValue = 该维度的取值）。
     */
    public record DimUsage(String dimKey, String dimValue, TokenUsage tokens) {
    }

    /**
     * 未配价标注项：该 (provider, model, 档位) 在窗口内有 token 用量，但事件时点
     * 无生效单价行——对应成本分量缺失（null 语义），cost 分桶只含已配价部分。
     */
    public record UnpricedUsage(String provider, String model, TokenKind tokenKind) {
    }
}
