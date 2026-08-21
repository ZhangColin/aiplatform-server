package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.util.List;

import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;

/**
 * 项目用量响应（片5c 基础版，A1 §2.5）：总量 + 分模型 + 分角色聚合，度量单位
 * token——金额（平台成本）与按期聚合随 A6 扩展（票 #29）。
 *
 * @param projectId 项目标识（subject）
 * @param total     总量（五档分列）
 * @param byModel   分模型聚合（provider + model 为 A6 单价表匹配键）
 * @param byRole    分角色聚合（dims.role 维度；角色为稳定键 + 展示名）
 */
public record ProjectUsageResponse(
        String projectId,
        TokenUsage total,
        List<ModelUsage> byModel,
        List<RoleUsage> byRole
) {

    public ProjectUsageResponse {
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
        byRole = byRole == null ? List.of() : List.copyOf(byRole);
    }

    /**
     * 分模型聚合项。
     */
    public record ModelUsage(String provider, String model, TokenUsage tokens) {
    }

    /**
     * 分角色聚合项（role = RolePreset 稳定键，roleLabel = 展示名）。
     */
    public record RoleUsage(String role, String roleLabel, TokenUsage tokens) {
    }
}
