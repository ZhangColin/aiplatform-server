package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;

/**
 * 项目用量响应（A1 §2.5 + A6 §3）：总量 + 平台成本 + 分模型 + 分角色 + 按期聚合。
 *
 * <p><b>平台成本口径</b>（零商业概念）：{@code cost} = token 用量 × 事件时点生效
 * 单价的机械乘法，按币种分桶不折算（键 = ISO 4217 币种码），无加价/售价；
 * {@code unpriced} = 有 token 用量但事件时点无生效单价的 (provider, model, 档位)——
 * 其分量不进 cost（不伪装 0），前端据此示「未配价」。{@code byIteration} 按
 * dims.iterationId 聚合（run 发起时快照）；期后修复 run 不带 iterationId——入
 * 项目总量与分角色（FIX），不入任何期桶（收口期成本定格）。</p>
 *
 * @param projectId   项目标识（subject）
 * @param total       总量（五档分列）
 * @param cost        平台成本（币种分桶；全未配价/无事件时为空 Map）
 * @param unpriced    未配价标注（cost 不含这些分量）
 * @param byModel     分模型聚合（provider + model 为单价表匹配键）
 * @param byRole      分角色聚合（dims.role 维度；角色为稳定键 + 展示名）
 * @param byIteration 按期聚合（dims.iterationId 维度，iterationId 数值升序）
 */
public record ProjectUsageResponse(
        String projectId,
        TokenUsage total,
        Map<String, BigDecimal> cost,
        List<UnpricedUsage> unpriced,
        List<ModelUsage> byModel,
        List<RoleUsage> byRole,
        List<IterationUsage> byIteration
) {

    public ProjectUsageResponse {
        // 保序拷贝：cost 键序 = 服务层排定的币种码序（API 输出确定性）
        cost = cost == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(cost));
        unpriced = unpriced == null ? List.of() : List.copyOf(unpriced);
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
        byRole = byRole == null ? List.of() : List.copyOf(byRole);
        byIteration = byIteration == null ? List.of() : List.copyOf(byIteration);
    }

    /**
     * 分模型聚合项。
     */
    public record ModelUsage(String provider, String model, TokenUsage tokens) {
    }

    /**
     * 分角色聚合项（role = RolePreset 稳定键或 FIX/RESUME 用途标记，roleLabel =
     * 展示名；非 preset 的用途标记 roleLabel 为 null）。
     */
    public record RoleUsage(String role, String roleLabel, TokenUsage tokens) {
    }

    /**
     * 按期聚合项（iterationId = dims 快照值；seq = 期序号「第 N 期」展示素材，
     * 期行已不可得时为 null）。
     */
    public record IterationUsage(String iterationId, Integer seq, TokenUsage tokens) {
    }

    /**
     * 未配价标注项（tokenKind 为 Integer code + tokenKindName 随附，#34 收敛房规）。
     */
    public record UnpricedUsage(String provider, String model, TokenKind tokenKind,
                                String tokenKindName) {
    }
}
