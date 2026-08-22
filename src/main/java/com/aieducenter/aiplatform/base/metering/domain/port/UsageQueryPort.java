package com.aieducenter.aiplatform.base.metering.domain.port;

import java.time.Instant;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;

/**
 * 用量查询端口（CLIENT 直调）：按 subject 聚合（总量 + 平台成本 + 未配价标注 +
 * 分模型/分维度——按期聚合 = 业务层过滤 dims.iterationId 桶，A6 §3 透传缝）。
 * 业务层端点（{@code GET /api/projects/{id}/usage}）经本端口消费。
 *
 * <p>迁出独立计量服务时本端口换 REST 适配器，签名不动（A1 §2.1）。</p>
 */
@Port(PortType.CLIENT)
public interface UsageQueryPort {

    /**
     * 按 subject 聚合用量。时间窗半开区间 {@code [from, to)}，两侧 null = 该侧不限；
     * subject 不透明（底座不解释其存在性），无事件返回全零而非错误。
     */
    UsageSummary bySubject(String subject, Instant from, Instant to);
}
