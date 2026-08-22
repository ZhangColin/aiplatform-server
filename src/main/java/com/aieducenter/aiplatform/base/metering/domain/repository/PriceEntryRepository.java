package com.aieducenter.aiplatform.base.metering.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;

/**
 * 单价行仓储（{@code met_price_entries}）：base.metering 私有表的写面——v1 维护
 * 入口 = 启动种子 + 手工 SQL（A6 §1）。换算查询不走本仓储（读侧原生 SQL 见
 * {@link UsageEventAggregations}）；改价「关旧行开新行」v1 经手工 SQL 落，维护
 * API 挂 fog「管理后台」。
 */
public interface PriceEntryRepository extends BaseRepository<PriceEntry, Long> {

    /**
     * 该匹配键是否已有任意单价行（含已关行）——种子幂等判据：有行即跳过
     * （手工维护接管优先，种子不插手不改价）。
     */
    boolean existsByProviderAndModelAndTokenKind(String provider, String model, TokenKind tokenKind);
}
