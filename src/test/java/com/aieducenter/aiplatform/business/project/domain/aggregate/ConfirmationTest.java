package com.aieducenter.aiplatform.business.project.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.business.project.domain.enums.ConfirmationDecision;
import com.aieducenter.aiplatform.business.project.domain.enums.ConfirmationKind;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 确认记录不变量（A3 §3）：append-only 留痕——approve 也留痕、驳回 reason 必填、
 * decidedAt 落痕时取定、account_id 第一天记 approver。
 */
class ConfirmationTest {

    /** 已落库期的 id（留痕锚定真实期；期本身的迁移归 Iteration 聚合）。 */
    private static final Long ITERATION_ID = 9001L;

    @Test
    void given_gate_passed_when_approve_of_then_recorded_with_decider() {
        Confirmation confirmation = Confirmation.approveOf(ITERATION_ID,
                ConfirmationKind.REQUIREMENT, 42L);

        assertThat(confirmation.getIterationId()).isEqualTo(ITERATION_ID);
        assertThat(confirmation.getKind()).isEqualTo(ConfirmationKind.REQUIREMENT);
        assertThat(confirmation.getDecision()).isEqualTo(ConfirmationDecision.APPROVED);
        assertThat(confirmation.getAccountId()).isEqualTo(42L);
        assertThat(confirmation.getDecidedAt()).isNotNull();
        // 通过无 reason（无体端点，拍板即全部事实）
        assertThat(confirmation.getReason()).isNull();
    }

    @Test
    void given_gate_rejected_when_reject_of_then_reason_kept_trimmed() {
        Confirmation confirmation = Confirmation.rejectOf(ITERATION_ID,
                ConfirmationKind.ACCEPTANCE, 42L, "  首页布局与需求不符 ");

        assertThat(confirmation.getDecision()).isEqualTo(ConfirmationDecision.REJECTED);
        assertThat(confirmation.getReason()).isEqualTo("首页布局与需求不符");
        assertThat(confirmation.getDecidedAt()).isNotNull();
    }

    @Test
    void given_no_session_when_record_then_account_nullable() {
        // 无会话上下文（本地冒烟等）可空——字段在，值后补
        Confirmation confirmation = Confirmation.approveOf(ITERATION_ID,
                ConfirmationKind.DEVELOPMENT, null);

        assertThat(confirmation.getAccountId()).isNull();
    }

    @Test
    void given_blank_reason_when_reject_of_then_domain_error() {
        assertThatThrownBy(() -> Confirmation.rejectOf(ITERATION_ID,
                ConfirmationKind.DEMO, 42L, " "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.REJECT_REASON_REQUIRED.message());
    }

    @Test
    void given_incomplete_fields_when_record_then_domain_error() {
        assertThatThrownBy(() -> Confirmation.approveOf(null,
                ConfirmationKind.REQUIREMENT, 42L))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> Confirmation.rejectOf(ITERATION_ID, null,
                42L, "不对"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_FIELDS_INCOMPLETE.message());
    }
}
