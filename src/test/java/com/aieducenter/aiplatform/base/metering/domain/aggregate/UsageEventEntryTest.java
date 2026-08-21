package com.aieducenter.aiplatform.base.metering.domain.aggregate;

import java.time.Instant;
import java.util.Map;

import com.cartisan.core.exception.DomainException;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用量事件聚合根单测（规范 §8.1 聚合根测试必须）：必填校验、dims 归一、五档映射。
 */
class UsageEventEntryTest {

    private static final Instant TS = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void given_valid_event_when_of_then_fields_mapped_and_tokens_readable() {
        UsageEvent event = new UsageEvent("evt-1", TS, "proj-1", "run-1", "session-1",
                "deepseek", "deepseek-v4-pro", "opencode",
                Map.of("role", "DEV"), new TokenUsage(100, 50, 20, 5, 0));

        UsageEventEntry entry = UsageEventEntry.of(event);

        assertThat(entry.getEventId()).isEqualTo("evt-1");
        assertThat(entry.getTs()).isEqualTo(TS);
        assertThat(entry.getSubject()).isEqualTo("proj-1");
        assertThat(entry.getProvider()).isEqualTo("deepseek");
        assertThat(entry.getDims()).containsEntry("role", "DEV");
        // 五档逐列映射，tokens() 读回协议形
        assertThat(entry.tokens()).isEqualTo(new TokenUsage(100, 50, 20, 5, 0));
        assertThat(entry.getId()).isEqualTo("evt-1");
    }

    @Test
    void given_null_or_empty_dims_when_of_then_normalized_to_null() {
        assertThat(UsageEventEntry.of(event(null)).getDims()).isNull();
        assertThat(UsageEventEntry.of(event(Map.of())).getDims()).isNull();
    }

    @Test
    void given_missing_required_fields_when_of_then_rejected() {
        assertIncomplete(new UsageEvent(" ", TS, "proj-1", null, null,
                "deepseek", "deepseek-v4-pro", "opencode", null, TokenUsage.ZERO));
        assertIncomplete(new UsageEvent("evt-1", null, "proj-1", null, null,
                "deepseek", "deepseek-v4-pro", "opencode", null, TokenUsage.ZERO));
        assertIncomplete(new UsageEvent("evt-1", TS, null, null, null,
                "deepseek", "deepseek-v4-pro", "opencode", null, TokenUsage.ZERO));
        assertIncomplete(new UsageEvent("evt-1", TS, "proj-1", null, null,
                null, "deepseek-v4-pro", "opencode", null, TokenUsage.ZERO));
        assertIncomplete(new UsageEvent("evt-1", TS, "proj-1", null, null,
                "deepseek", null, "opencode", null, TokenUsage.ZERO));
        assertIncomplete(new UsageEvent("evt-1", TS, "proj-1", null, null,
                "deepseek", "deepseek-v4-pro", " ", null, TokenUsage.ZERO));
        assertIncomplete(new UsageEvent("evt-1", TS, "proj-1", null, null,
                "deepseek", "deepseek-v4-pro", "opencode", null, null));
        assertIncomplete(null);
    }

    // runId/sessionId 可空（非 run 级来源），单列验证不抛
    @Test
    void given_no_run_or_session_when_of_then_accepted() {
        UsageEvent event = new UsageEvent("evt-2", TS, "proj-1", null, null,
                "deepseek", "deepseek-v4-pro", "opencode", null, TokenUsage.ZERO);

        assertThat(UsageEventEntry.of(event).getRunId()).isNull();
        assertThat(UsageEventEntry.of(event).getSessionId()).isNull();
    }

    private static UsageEvent event(Map<String, String> dims) {
        return new UsageEvent("evt-1", TS, "proj-1", "run-1", "session-1",
                "deepseek", "deepseek-v4-pro", "opencode", dims, TokenUsage.ZERO);
    }

    private static void assertIncomplete(UsageEvent event) {
        assertThatThrownBy(() -> UsageEventEntry.of(event))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("用量事件字段不完整");
    }
}
