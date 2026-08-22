package com.aieducenter.aiplatform.base.metering.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageQueryPort;
import com.aieducenter.aiplatform.base.metering.domain.repository.PriceEntryRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台成本换算集成测试（票 #29 验收口径）：跨改价边界两事件各按各时点单价（历史
 * 成本不漂移）；缺价分量 null（unpriced 标注，不伪装 0、不阻断查询）；币种分桶
 * 不相加。经端口全链路（sink → 落库 → 原生 SQL 换算），事件与单价行走真实库。
 *
 * <p>单价/事件 fixture 用独立 provider {@code testprov}——与启动种子行（deepseek
 * 现役模型）不撞，种子行不在本类断言面内（种子验收见 {@code PriceEntrySeederTest}）。</p>
 */
@SpringBootTest
class MeteringCostAggregationTest {

    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-03T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-07-04T00:00:00Z");

    private static final String SUBJ = "cost-proj";
    private static final String PROVIDER = "testprov";
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency CNY = Currency.getInstance("CNY");

    @Autowired
    private UsageEventSink usageEventSink;

    @Autowired
    private UsageQueryPort usageQueryPort;

    @Autowired
    private PriceEntryRepository priceEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM met_usage_events");
        jdbcTemplate.update("DELETE FROM met_price_entries WHERE provider = '" + PROVIDER + "'");
    }

    @Test
    void given_price_change_when_events_straddle_boundary_then_each_priced_at_its_time() {
        // 改价 = 关旧行开新行：$1/1M 生效 [T0, T2)，$2/1M 自 T2 起
        PriceEntry oldRow = priceEntryRepository.save(PriceEntry.open(PROVIDER, "m1",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0));
        oldRow.close(T2);
        priceEntryRepository.save(oldRow);
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m1",
                TokenKind.INPUT, new BigDecimal("0.000002"), "USD", T2));

        report("evt-before", T1, "m1", new TokenUsage(1000, 0, 0, 0, 0));  // 旧价时段
        report("evt-after", T3, "m1", new TokenUsage(1000, 0, 0, 0, 0));   // 新价时段

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 各按各时点单价：1000×$1/1M + 1000×$2/1M = $0.003（历史事件不随改价漂移）
        assertThat(summary.cost()).containsOnlyKeys(USD);
        assertThat(summary.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.003"));
        assertThat(summary.unpriced()).isEmpty();
    }

    @Test
    void given_no_price_row_when_query_then_cost_empty_unpriced_marked_not_faked_zero() {
        report("evt-noprice", T1, "m-none", new TokenUsage(100, 50, 0, 0, 0));

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // token 照记照聚合，查询不阻断
        assertThat(summary.total()).isEqualTo(new TokenUsage(100, 50, 0, 0, 0));
        // 缺价 → cost 不出分量（空桶，不是 0）
        assertThat(summary.cost()).isEmpty();
        // unpriced 只标有用量的档位（cache_read 等零量档不标）
        assertThat(summary.unpriced()).containsExactly(
                new UsageSummary.UnpricedUsage(PROVIDER, "m-none", TokenKind.INPUT),
                new UsageSummary.UnpricedUsage(PROVIDER, "m-none", TokenKind.OUTPUT));
    }

    @Test
    void given_partial_price_when_query_then_priced_parts_bucketed_rest_marked_unpriced() {
        // 只有 input 有价：output/cache_write 有量无价
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-part",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0));
        report("evt-part", T1, "m-part", new TokenUsage(1000, 500, 0, 7, 0));

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 已配价分量照算（不因部分缺价阻断），未配价分量不进 cost
        assertThat(summary.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.001"));
        assertThat(summary.unpriced()).containsExactly(
                new UsageSummary.UnpricedUsage(PROVIDER, "m-part", TokenKind.OUTPUT),
                new UsageSummary.UnpricedUsage(PROVIDER, "m-part", TokenKind.CACHE_WRITE));
    }

    @Test
    void given_multi_currency_when_query_then_buckets_not_merged() {
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-usd",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0));
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-cny",
                TokenKind.INPUT, new BigDecimal("0.000001"), "CNY", T0));
        report("evt-usd", T1, "m-usd", new TokenUsage(1000, 0, 0, 0, 0));
        report("evt-cny", T1, "m-cny", new TokenUsage(1000, 0, 0, 0, 0));

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 币种分桶不折算不相加（A6 §2：引汇率 = 过度设计）
        assertThat(summary.cost()).containsOnlyKeys(USD, CNY);
        assertThat(summary.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.001"));
        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.001"));
    }

    @Test
    void given_window_when_query_then_cost_and_unpriced_scoped_to_window() {
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m1",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0));
        report("evt-in", T1, "m1", new TokenUsage(1000, 0, 0, 0, 0));     // 窗口内
        report("evt-out", T3, "m-none", new TokenUsage(100, 0, 0, 0, 0)); // 窗口外（无价模型）

        UsageSummary windowed = usageQueryPort.bySubject(SUBJ, T0, T2);

        // [T0, T2)：只算窗口内事件——成本与未配价标注同口径收窄
        assertThat(windowed.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.001"));
        assertThat(windowed.unpriced()).isEmpty();
        // 全窗口：窗口外无价事件进 unpriced
        assertThat(usageQueryPort.bySubject(SUBJ, null, null).unpriced())
                .containsExactly(new UsageSummary.UnpricedUsage(PROVIDER, "m-none", TokenKind.INPUT));
    }

    @Test
    void given_all_five_kinds_priced_when_query_then_every_kind_costed() {
        // 五档各配各价（含 cache_write/reasoning——Anthropic/OpenAI 口径的档位也要能计价）
        for (TokenKind kind : TokenKind.values()) {
            priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-all", kind,
                    new BigDecimal("0.000001"), "USD", T0));
        }
        report("evt-all", T1, "m-all", new TokenUsage(1000, 500, 200, 100, 50));

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // (1000+500+200+100+50) × $1/1M = $0.00185——五档都进换算，不漏档
        assertThat(summary.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.00185"));
        assertThat(summary.unpriced()).isEmpty();
    }

    @Test
    void given_event_before_price_effective_when_query_then_unpriced_for_gap_period() {
        // 单价自 T2 起生效：T1 的事件落在生效区间之前（改价不溯及）
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m1",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T2));
        report("evt-early", T1, "m1", new TokenUsage(1000, 0, 0, 0, 0));
        report("evt-late", T3, "m1", new TokenUsage(1000, 0, 0, 0, 0));

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 只有 T3 事件有价：成本 = $0.001；T1 事件落未配价（同一匹配键逐事件按时点判定）
        assertThat(summary.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.001"));
        assertThat(summary.unpriced()).containsExactly(
                new UsageSummary.UnpricedUsage(PROVIDER, "m1", TokenKind.INPUT));
    }

    // ---------- fixture ----------

    private void report(String eventId, Instant ts, String model, TokenUsage tokens) {
        usageEventSink.report(new UsageEvent(eventId, ts, SUBJ, "run-1", "session-1",
                PROVIDER, model, "opencode", Map.of(), tokens));
    }
}
