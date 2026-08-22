package com.aieducenter.aiplatform.base.metering.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.repository.PriceEntryRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单价启动种子验收（票 #29）：现役模型 deepseek-v4-pro / deepseek-v4-flash 各三档
 * （input / cache_read / output，USD，官方定价页 peak 档——2026-08-22 核对
 * api-docs.deepseek.com/quick_start/pricing）；幂等 = 匹配键已有任意行（含手工
 * 维护的已关行）即跳过，种子不插手。
 *
 * <p>上下文启动即播种（ApplicationRunner），本类以库内真实行为为准。</p>
 */
@SpringBootTest
class PriceEntrySeederTest {

    @Autowired
    private PriceEntryRepository priceEntryRepository;

    @Autowired
    private PriceEntrySeeder seeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // 恢复种子行原状（改价模拟用例动过 effective_to），不碰他 provider 的测试行
        jdbcTemplate.update("UPDATE met_price_entries SET effective_to = NULL"
                + " WHERE provider = 'deepseek'");
    }

    @Test
    void given_context_booted_then_six_seed_rows_with_official_peak_values() {
        List<PriceEntry> rows = seedRows();

        assertThat(rows).hasSize(6);
        // 数值与官方定价页 peak 档逐行核对（$x/1M ÷ 1e6 = 每 token 单价）
        assertSeed(rows, "deepseek-v4-pro", TokenKind.INPUT, "0.00000132");
        assertSeed(rows, "deepseek-v4-pro", TokenKind.CACHE_READ, "0.000000044");
        assertSeed(rows, "deepseek-v4-pro", TokenKind.OUTPUT, "0.00000396");
        assertSeed(rows, "deepseek-v4-flash", TokenKind.INPUT, "0.00000044");
        assertSeed(rows, "deepseek-v4-flash", TokenKind.CACHE_READ, "0.000000014");
        assertSeed(rows, "deepseek-v4-flash", TokenKind.OUTPUT, "0.00000132");
        // 生效区间：统一自 2026-01-01 起敞口（覆盖存量事件；改价史归手工 SQL）
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getEffectiveFrom()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(row.getEffectiveTo()).isNull();
            assertThat(row.getCurrency()).isEqualTo("USD");
        });
    }

    @Test
    void given_seed_rows_present_when_rerun_then_no_duplicates() {
        int before = seedRows().size();

        seeder.run(null);

        assertThat(seedRows()).hasSize(before); // 幂等：已有行全跳过，零新插
    }

    @Test
    void given_manually_closed_row_when_rerun_then_seed_does_not_interfere() {
        // 手工 SQL 维护场景：pro/input 被关行（改价上半步）——种子不得复活/重开
        PriceEntry closed = seedRows().stream()
                .filter(row -> row.getModel().equals("deepseek-v4-pro")
                        && row.getTokenKind() == TokenKind.INPUT)
                .findFirst().orElseThrow();
        closed.close(Instant.parse("2026-08-01T00:00:00Z"));
        priceEntryRepository.save(closed);

        seeder.run(null);

        List<PriceEntry> rows = seedRows();
        assertThat(rows).hasSize(6); // 不新开行（已关行也算「已有行」）
        assertThat(rows.stream().filter(row -> row.getId().equals(closed.getId())).findFirst()
                .orElseThrow().getEffectiveTo())
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z")); // 手工关行不被回滚
    }

    // ---------- 内部 ----------

    private List<PriceEntry> seedRows() {
        return priceEntryRepository.findAll().stream()
                .filter(row -> row.getProvider().equals("deepseek"))
                .toList();
    }

    private void assertSeed(List<PriceEntry> rows, String model, TokenKind kind, String price) {
        PriceEntry row = rows.stream()
                .filter(r -> r.getModel().equals(model) && r.getTokenKind() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺种子行：" + model + " / " + kind));
        assertThat(row.getUnitPrice()).isEqualByComparingTo(new BigDecimal(price));
    }
}
