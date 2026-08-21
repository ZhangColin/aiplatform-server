package com.aieducenter.aiplatform.base.agentengine.domain.aggregate;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 等待点聚合（票 #21）：raise 登记校验与 waitId 生成、三途单向关闭
 * （settle/expire/cancel）、非 PENDING 的非法跳变防护。
 */
class AgentWaitTest {

    private static final Instant RAISED_AT = Instant.parse("2026-08-22T08:00:00Z");
    private static final Instant SETTLED_AT = Instant.parse("2026-08-22T08:05:00Z");

    @Test
    void given_complete_fields_when_raise_then_pending_with_generated_wait_id() {
        AgentWait wait = AgentWait.raise(4242L, "ses_1", "run-1", WaitKind.QUESTION,
                "que_1", "用哪个框架?", Map.of("id", "que_1"), RAISED_AT);

        assertThat(wait.getWaitId()).isNotBlank();
        assertThat(wait.getStatus()).isEqualTo(WaitStatus.PENDING);
        assertThat(wait.getKind()).isEqualTo(WaitKind.QUESTION);
        assertThat(wait.getEngineRef()).isEqualTo("que_1");
        assertThat(wait.getSummary()).isEqualTo("用哪个框架?");
        assertThat(wait.getBody()).containsEntry("id", "que_1");
        assertThat(wait.getSettleOutcome()).isNull();
        assertThat(wait.getSettledAt()).isNull();
        assertThat(wait.getId()).isEqualTo(wait.getWaitId()); // 聚合 ID = 稳定标识
    }

    @Test
    void given_blank_summary_when_raise_then_stored_null_body_normalized() {
        AgentWait wait = AgentWait.raise(4242L, "ses_1", "run-1", WaitKind.PERMISSION,
                "per_1", " ", null, RAISED_AT);

        assertThat(wait.getSummary()).isNull();
        assertThat(wait.getBody()).isNull();
    }

    @Test
    void given_missing_fields_when_raise_then_rejected() {
        assertThatThrownBy(() -> AgentWait.raise(4242L, " ", "run-1", WaitKind.QUESTION,
                "que_1", null, null, RAISED_AT))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(AgentEngineMessage.WAIT_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> AgentWait.raise(4242L, "ses_1", "run-1", null,
                "que_1", null, null, RAISED_AT))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> AgentWait.raise(4242L, "ses_1", "run-1", WaitKind.QUESTION,
                "que_1", null, null, null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_pending_wait_when_settle_then_outcome_recorded() {
        AgentWait wait = raiseQuestion();

        wait.settle(WaitOutcome.ANSWERED, SETTLED_AT);

        assertThat(wait.getStatus()).isEqualTo(WaitStatus.SETTLED);
        assertThat(wait.getSettleOutcome()).isEqualTo(WaitOutcome.ANSWERED);
        assertThat(wait.getSettledAt()).isEqualTo(SETTLED_AT);
    }

    @Test
    void given_pending_wait_when_expire_or_cancel_then_terminal_without_outcome() {
        AgentWait expired = raiseQuestion();
        expired.expire(SETTLED_AT);
        assertThat(expired.getStatus()).isEqualTo(WaitStatus.EXPIRED);
        assertThat(expired.getSettleOutcome()).isNull();

        AgentWait cancelled = raiseQuestion();
        cancelled.cancel(SETTLED_AT);
        assertThat(cancelled.getStatus()).isEqualTo(WaitStatus.CANCELLED);
        assertThat(cancelled.getSettleOutcome()).isNull();
    }

    @Test
    void given_settled_wait_when_close_again_then_illegal_transition_rejected() {
        AgentWait wait = raiseQuestion();
        wait.settle(WaitOutcome.ANSWERED, SETTLED_AT);

        assertThatThrownBy(() -> wait.settle(WaitOutcome.DEFERRED, SETTLED_AT))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(AgentEngineMessage.WAIT_CONFLICT.message());
        assertThatThrownBy(() -> wait.expire(SETTLED_AT))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> wait.cancel(SETTLED_AT))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_pending_wait_when_close_without_time_then_rejected() {
        AgentWait wait = raiseQuestion();

        assertThatThrownBy(() -> wait.settle(WaitOutcome.ANSWERED, null))
                .isInstanceOf(DomainException.class);
    }

    private AgentWait raiseQuestion() {
        return AgentWait.raise(4242L, "ses_1", "run-1", WaitKind.QUESTION,
                "que_1", "用哪个框架?", Map.of("id", "que_1"), RAISED_AT);
    }
}
