package com.aieducenter.aiplatform.business.project.domain.aggregate;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.business.project.domain.enums.ConfirmationDecision;
import com.aieducenter.aiplatform.business.project.domain.enums.ConfirmationKind;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * 确认记录（{@code prj_confirmations}，A3 §3）：门决策的 append-only 留痕——
 * 只增不改不删（行随期 FK 级联），approve 也留痕（交付审计 + A5 纪要素材）。
 *
 * <p>「谁在何时拍板」是业务事实：{@code decidedAt} 在落痕时取定（非审计时间），
 * {@code accountId} 从第一天记（多账号是常态，A2 既定 owner 列同理由；无会话
 * 上下文可空）。驳回 reason 必填（A3 §3：驳回反馈同时是纪要来源）；通过无
 * reason（无体端点，拍板即全部事实）。</p>
 */
@Entity
@Table(name = "prj_confirmations")
@Aggregate
@Getter
public class Confirmation extends Auditable implements AggregateRoot<Confirmation, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "iteration_id", nullable = false, updatable = false)
    private Long iterationId;

    @Column(name = "kind", nullable = false, updatable = false)
    private ConfirmationKind kind;

    @Column(name = "decision", nullable = false, updatable = false)
    private ConfirmationDecision decision;

    /** 驳回理由（驳回必填；通过恒空）。 */
    @Column(name = "reason", updatable = false, length = 1000)
    private String reason;

    /** 拍板账号（idn_accounts 软引用；无会话上下文可空）。 */
    @Column(name = "account_id", updatable = false)
    private Long accountId;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private LocalDateTime decidedAt;

    protected Confirmation() {
    }

    private Confirmation(Long iterationId, ConfirmationKind kind,
                         ConfirmationDecision decision, String reason, Long accountId) {
        if (iterationId == null || kind == null || decision == null) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        if (decision == ConfirmationDecision.REJECTED
                && (reason == null || reason.isBlank())) {
            throw new DomainException(ProjectMessage.REJECT_REASON_REQUIRED);
        }
        this.iterationId = iterationId;
        this.kind = kind;
        this.decision = decision;
        this.reason = normalizeReason(reason);
        this.accountId = accountId;
        this.decidedAt = LocalDateTime.now();
    }

    /**
     * 通过留痕（驳回停留/推进收口的迁移归期聚合，本实体只记事实）。
     * iterationId 必须是已落库期的 id——留痕锚定真实期。
     */
    public static Confirmation approveOf(Long iterationId, ConfirmationKind kind,
                                         Long accountId) {
        return new Confirmation(iterationId, kind, ConfirmationDecision.APPROVED,
                null, accountId);
    }

    /**
     * 驳回留痕（reason 必填——A3 §3）。
     */
    public static Confirmation rejectOf(Long iterationId, ConfirmationKind kind,
                                        Long accountId, String reason) {
        return new Confirmation(iterationId, kind, ConfirmationDecision.REJECTED,
                reason, accountId);
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }

    private static String normalizeReason(String reason) {
        return reason == null ? null : reason.strip();
    }
}
