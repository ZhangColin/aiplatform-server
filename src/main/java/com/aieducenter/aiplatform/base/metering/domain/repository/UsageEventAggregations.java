package com.aieducenter.aiplatform.base.metering.domain.repository;

import java.time.Instant;

import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;

/**
 * 用量事件读侧聚合接口（实现：infrastructure/persistence 的
 * {@code UsageEventAggregationsImpl}，jsonb 维度聚合需原生 SQL）。
 *
 * <p>与 {@link UsageEventRepository}（写面）分离：读侧是原生 SQL 聚合，不走
 * JPA 派生查询；接口留在 domain 由 infrastructure 实现（端口-适配器同构）。</p>
 */
public interface UsageEventAggregations {

    /**
     * 按 subject 聚合（总量 + 分模型 + 分维度）。时间窗半开区间 {@code [from, to)}，
     * 两侧 null = 该侧不限；subject 无事件返回全零 total 与空列表，不是错误。
     */
    UsageSummary aggregateBySubject(String subject, Instant from, Instant to);
}
