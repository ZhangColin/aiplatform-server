package com.aieducenter.aiplatform.base.metering.application;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageQueryPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 计量基座集成测试（票 #16 验收：eventId 幂等 + bySubject 聚合正确性）。
 * 经端口全链路（{@code MeteringLocalAdapter} → 应用服务 → 落库/原生 SQL 聚合），
 * 以库内真实行为为准（B0 §5.2：副作用以真实状态为准，非事件自述）。
 */
@SpringBootTest
class MeteringAppServiceTest {

    private static final Instant T1 = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-08-03T00:00:00Z");
    private static final Instant T4 = Instant.parse("2026-08-04T00:00:00Z");

    private static final String PROJ_1 = "proj-1";
    private static final String PROJ_2 = "proj-2";

    @Autowired
    private UsageEventSink usageEventSink;

    @Autowired
    private UsageQueryPort usageQueryPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM met_usage_events");
    }

    @Test
    void given_first_report_when_report_then_row_recorded_with_dims_passthrough() {
        usageEventSink.report(event("evt-1", PROJ_1, T1, "deepseek", "deepseek-v4-pro",
                Map.of("role", "DEV", "stage", "DEV"), new TokenUsage(100, 50, 20, 0, 0)));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE event_id = 'evt-1'", Integer.class))
                .isEqualTo(1);
        // 五档分列 + dims jsonb 透传（底座不解释，原样可取）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT input FROM met_usage_events WHERE event_id = 'evt-1'", Long.class))
                .isEqualTo(100L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cache_read FROM met_usage_events WHERE event_id = 'evt-1'", Long.class))
                .isEqualTo(20L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT dims->>'role' FROM met_usage_events WHERE event_id = 'evt-1'", String.class))
                .isEqualTo("DEV");
    }

    @Test
    void given_same_event_id_when_report_twice_then_counted_once_no_error() {
        UsageEvent event = event("evt-dup", PROJ_1, T1, "deepseek", "deepseek-v4-pro",
                Map.of("role", "DEV"), new TokenUsage(100, 50, 0, 0, 0));

        usageEventSink.report(event);
        usageEventSink.report(event);   // 幂等：静默吸收，不抛错

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events", Integer.class)).isEqualTo(1);
        assertThat(usageQueryPort.bySubject(PROJ_1, null, null).total())
                .isEqualTo(new TokenUsage(100, 50, 0, 0, 0));   // 不重复计入
    }

    @Test
    void given_incomplete_event_when_report_then_rejected_nothing_recorded() {
        assertThatThrownBy(() -> usageEventSink.report(new UsageEvent(null, T1, PROJ_1,
                null, null, "deepseek", "deepseek-v4-pro", "opencode", null,
                new TokenUsage(1, 1, 0, 0, 0))))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("用量事件字段不完整");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events", Integer.class)).isZero();
    }

    @Test
    void given_multi_model_multi_dim_events_when_by_subject_then_total_model_dim_aggregates() {
        seed();

        UsageSummary summary = usageQueryPort.bySubject(PROJ_1, null, null);

        assertThat(summary.subject()).isEqualTo(PROJ_1);
        // 总量 = A + B + C + E（null 窗口全含）
        assertThat(summary.total()).isEqualTo(new TokenUsage(1111, 57, 23, 4, 5));
        // 分模型（provider × model，排序稳定）
        assertThat(summary.byModel()).containsExactly(
                new UsageSummary.ModelUsage("anthropic", "claude-fable-5",
                        new TokenUsage(1, 2, 3, 4, 5)),
                new UsageSummary.ModelUsage("deepseek", "deepseek-v4-flash",
                        new TokenUsage(10, 5, 0, 0, 0)),
                new UsageSummary.ModelUsage("deepseek", "deepseek-v4-pro",
                        new TokenUsage(1100, 50, 20, 0, 0)));
        // 分维度：事件内每个 (key, value) 各成一桶
        assertThat(summary.byDims()).containsExactly(
                new UsageSummary.DimUsage("role", "BA", new TokenUsage(10, 5, 0, 0, 0)),
                new UsageSummary.DimUsage("role", "DEV", new TokenUsage(1101, 52, 23, 4, 5)),
                new UsageSummary.DimUsage("stage", "BA", new TokenUsage(10, 5, 0, 0, 0)),
                new UsageSummary.DimUsage("stage", "DEV", new TokenUsage(1101, 52, 23, 4, 5)));
    }

    @Test
    void given_half_open_window_when_by_subject_then_from_inclusive_to_exclusive() {
        seed();

        // [T2, T3)：只含 T2 的 B（T1 的 A 被 from 排除；T3 的 C 被 to 排除；T4 的 E 排除）
        assertThat(usageQueryPort.bySubject(PROJ_1, T2, T3).byModel()).containsExactly(
                new UsageSummary.ModelUsage("deepseek", "deepseek-v4-flash",
                        new TokenUsage(10, 5, 0, 0, 0)));
        // [T2, null)：B + C + E
        assertThat(usageQueryPort.bySubject(PROJ_1, T2, null).total())
                .isEqualTo(new TokenUsage(1011, 7, 3, 4, 5));
        // (null, T2)：只含 A
        assertThat(usageQueryPort.bySubject(PROJ_1, null, T2).total())
                .isEqualTo(new TokenUsage(100, 50, 20, 0, 0));
    }

    @Test
    void given_other_subjects_when_by_subject_then_isolated() {
        seed();

        // proj-2 只有 D；proj-1 的事件不计入
        assertThat(usageQueryPort.bySubject(PROJ_2, null, null).total())
                .isEqualTo(new TokenUsage(9999, 0, 0, 0, 0));
    }

    @Test
    void given_event_without_dims_when_by_subject_then_total_counted_dims_empty() {
        usageEventSink.report(event("evt-bare", "proj-3", T1, "deepseek", "deepseek-v4-pro",
                null, new TokenUsage(7, 8, 0, 0, 0)));

        UsageSummary summary = usageQueryPort.bySubject("proj-3", null, null);

        assertThat(summary.total()).isEqualTo(new TokenUsage(7, 8, 0, 0, 0));
        assertThat(summary.byDims()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT dims FROM met_usage_events WHERE event_id = 'evt-bare'", String.class))
                .isNull();
    }

    @Test
    void given_unknown_subject_when_by_subject_then_zero_not_error() {
        UsageSummary summary = usageQueryPort.bySubject("nobody", T1, T4);

        assertThat(summary.total()).isEqualTo(TokenUsage.ZERO);
        assertThat(summary.byModel()).isEmpty();
        assertThat(summary.byDims()).isEmpty();
    }

    @Test
    void given_blank_subject_when_by_subject_then_rejected() {
        assertThatThrownBy(() -> usageQueryPort.bySubject(" ", null, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("必须指定 subject");
    }

    // ---------- fixture ----------

    private void seed() {
        // A：deepseek pro（DEV/DEV 两维）
        usageEventSink.report(event("evt-a", PROJ_1, T1, "deepseek", "deepseek-v4-pro",
                Map.of("role", "DEV", "stage", "DEV"), new TokenUsage(100, 50, 20, 0, 0)));
        // B：deepseek flash（BA/BA 两维）
        usageEventSink.report(event("evt-b", PROJ_1, T2, "deepseek", "deepseek-v4-flash",
                Map.of("role", "BA", "stage", "BA"), new TokenUsage(10, 5, 0, 0, 0)));
        // C：anthropic（DEV/DEV 两维，五档全非零）
        usageEventSink.report(event("evt-c", PROJ_1, T3, "anthropic", "claude-fable-5",
                Map.of("role", "DEV", "stage", "DEV"), new TokenUsage(1, 2, 3, 4, 5)));
        // D：他 subject（隔离面）
        usageEventSink.report(event("evt-d", PROJ_2, T1, "deepseek", "deepseek-v4-pro",
                Map.of("role", "DEV"), new TokenUsage(9999, 0, 0, 0, 0)));
        // E：窗口外候选（T4，半开区间验收用）
        usageEventSink.report(event("evt-e", PROJ_1, T4, "deepseek", "deepseek-v4-pro",
                Map.of("role", "DEV", "stage", "DEV"), new TokenUsage(1000, 0, 0, 0, 0)));
    }

    private UsageEvent event(String eventId, String subject, Instant ts, String provider,
                             String model, Map<String, String> dims, TokenUsage tokens) {
        return new UsageEvent(eventId, ts, subject, "run-1", "session-1",
                provider, model, "opencode", dims, tokens);
    }
}
