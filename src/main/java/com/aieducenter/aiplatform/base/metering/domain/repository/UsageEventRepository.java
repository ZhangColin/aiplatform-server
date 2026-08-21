package com.aieducenter.aiplatform.base.metering.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.base.metering.domain.aggregate.UsageEventEntry;

/**
 * 用量事件仓储（{@code met_usage_events}，append-only：save/existsById 幂等写面）。
 * 聚合 ID（eventId）由调用方生成显式赋值；读侧聚合见 {@link UsageEventAggregations}
 * （jsonb 维度聚合的原生 SQL 实现，不挂在 Spring Data 仓储上——fragment 路由在
 * cartisan 仓储装配下不可用，独立接口由 infrastructure 直接实现）。
 */
public interface UsageEventRepository extends BaseRepository<UsageEventEntry, String> {
}
