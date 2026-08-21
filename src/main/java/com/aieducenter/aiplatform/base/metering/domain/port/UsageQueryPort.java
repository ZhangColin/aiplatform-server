package com.aieducenter.aiplatform.base.metering.domain.port;

import java.time.Instant;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;

/**
 * 用量查询端口（CLIENT 直调）：按 subject 聚合（总量 + 分模型/分维度）。
 * 业务层端点（{@code GET /api/projects/{id}/usage}，片5c usage 基础版）经本端口
 * 消费；平台成本与按期聚合归 A6 扩展（票 #29）。
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
