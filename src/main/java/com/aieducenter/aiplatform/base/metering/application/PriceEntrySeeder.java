package com.aieducenter.aiplatform.base.metering.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.aieducenter.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.repository.PriceEntryRepository;

/**
 * 单价启动种子（A6 §1 维护入口之一：v1 = 手工 SQL + 本种子；维护 API 挂 fog
 * 「管理后台」）。现役模型 = demo 角色卡档位 deepseek-v4-pro / deepseek-v4-flash
 * （business RolePreset），DeepSeek 无缓存写/推理独立口径 → 每模型三行
 * （input / cache_read / output）。
 *
 * <p><b>数值口径</b>（A6 §1 纪律：以官方定价页实时值为准，不抄研究稿）：2026-08-22
 * 核对 api-docs.deepseek.com/quick_start/pricing——该页 2026-08-16 起分
 * peak/off-peak 时段价（off-peak = peak 一半，peak 时段 01:00–04:00、06:00–10:00
 * UTC）；单价表无时段维度，种子取 <b>peak 档（上限口径）</b>：成本可见性宁高勿低，
 * 精确到时段的换算是单价表演化（时段列/拆区间行），现在不预设。</p>
 *
 * <p><b>幂等</b>：匹配键（provider, model, 档位）已有任意行（含已关行）即跳过——
 * 手工 SQL 接管优先，种子不插手不改价。生效起点取 2026-01-01（覆盖平台存量事件；
 * 真实改价史经手工 SQL 关旧行开新行维护）。</p>
 */
@Component
@Slf4j
public class PriceEntrySeeder implements ApplicationRunner {

    /** 种子行生效起点：取早覆盖存量事件（改价史精确性归手工 SQL）。 */
    private static final Instant SEED_EFFECTIVE_FROM = Instant.parse("2026-01-01T00:00:00Z");

    private static final String USD = "USD";

    /** 种子行（官方定价页 peak 档，每 token USD）：$1.32/1M = 0.0000015 式换算。 */
    private static final List<SeedRow> SEED = List.of(
            new SeedRow("deepseek", "deepseek-v4-pro", TokenKind.INPUT, "0.00000132"),
            new SeedRow("deepseek", "deepseek-v4-pro", TokenKind.CACHE_READ, "0.000000044"),
            new SeedRow("deepseek", "deepseek-v4-pro", TokenKind.OUTPUT, "0.00000396"),
            new SeedRow("deepseek", "deepseek-v4-flash", TokenKind.INPUT, "0.00000044"),
            new SeedRow("deepseek", "deepseek-v4-flash", TokenKind.CACHE_READ, "0.000000014"),
            new SeedRow("deepseek", "deepseek-v4-flash", TokenKind.OUTPUT, "0.00000132"));

    private final PriceEntryRepository priceEntryRepository;

    public PriceEntrySeeder(PriceEntryRepository priceEntryRepository) {
        this.priceEntryRepository = priceEntryRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int inserted = 0;
        for (SeedRow row : SEED) {
            if (priceEntryRepository.existsByProviderAndModelAndTokenKind(
                    row.provider(), row.model(), row.tokenKind())) {
                continue; // 已有行（含手工维护/已关行）→ 种子不插手
            }
            priceEntryRepository.save(PriceEntry.open(row.provider(), row.model(),
                    row.tokenKind(), new BigDecimal(row.unitPrice()), USD, SEED_EFFECTIVE_FROM));
            inserted++;
        }
        log.info("[metering] 单价种子完成：新插 {} 行 / 共 {} 行候选（已有行跳过）", inserted, SEED.size());
    }

    private record SeedRow(String provider, String model, TokenKind tokenKind, String unitPrice) {
    }
}
