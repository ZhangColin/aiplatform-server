package com.aieducenter.aiplatform.business.task.domain.aggregate;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.business.task.domain.enums.TaskStatus;
import com.aieducenter.aiplatform.business.task.domain.enums.TaskType;
import com.aieducenter.aiplatform.business.task.domain.error.TaskMessage;

/**
 * 任务聚合根（{@code tsk_tasks}，A4 §1/§2）：测试外包循环的人任务。状态机
 * 全迁移与守卫收在聚合——已发布 → 执行中 → 已提交 → 已确认（终态）/已取消
 * （终态，仅前两态可进：已提交只能驳回）；非法迁移 DomainException TASK_002。
 *
 * <p>{@code submittedPayload} 是已提交载荷的 JSON 暂存（jsonb 透传，聚合不解释
 * ——首轮/复测两种形状的裁决在应用层，A4 §3）；{@code waitId} 是转任务来源的
 * 不透明引用（A1 §3，回填续跑随 #27）。确认模式不落列：由 {@link TaskType}
 * 推导。不软删除：终态行即历史（项目真删级联清行）。</p>
 */
@Entity
@Table(name = "tsk_tasks")
@Aggregate
@Getter
public class Task extends Auditable implements AggregateRoot<Task, Long> {

    /** 与库列宽对齐（V11 迁移）。 */
    public static final int TITLE_MAX_LENGTH = 200;
    public static final int REJECT_REASON_MAX_LENGTH = 1000;
    public static final int WAIT_ID_MAX_LENGTH = 50;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "type", nullable = false, updatable = false)
    private TaskType type;

    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /** 指派账号（idn_accounts 软引用；v1 指派必填，领取模式在雾）。 */
    @Column(name = "assignee_account_id", nullable = false, updatable = false)
    private Long assigneeAccountId;

    @Column(name = "status", nullable = false)
    private TaskStatus status;

    /** 转任务来源的不透明引用（可空；存档不解释）。 */
    @Column(name = "wait_id", updatable = false, length = WAIT_ID_MAX_LENGTH)
    private String waitId;

    /** 已提交载荷 JSON 原文（jsonb 透传；驳回重交覆盖，聚合不解释形状）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submitted_payload", columnDefinition = "jsonb")
    private String submittedPayload;

    @Column(name = "reject_reason", length = REJECT_REASON_MAX_LENGTH)
    private String rejectReason;

    /** 驳回时间（TASK_REJECTED 待办判定：IN_PROGRESS ∧ 非空；重新提交清空）。 */
    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    protected Task() {
    }

    private Task(Long projectId, TaskType type, String title, String content,
                 Long assigneeAccountId, String waitId) {
        if (projectId == null || type == null || assigneeAccountId == null
                || isBlank(title) || title.length() > TITLE_MAX_LENGTH
                || isBlank(content) || exceeds(waitId, WAIT_ID_MAX_LENGTH)) {
            throw new DomainException(TaskMessage.TASK_FIELDS_INCOMPLETE);
        }
        this.projectId = projectId;
        this.type = type;
        this.title = title.strip();
        this.content = content.strip();
        this.assigneeAccountId = assigneeAccountId;
        this.waitId = waitId;
        this.status = TaskStatus.PUBLISHED;
    }

    /**
     * 发布测试任务（指派必填；waitId 仅转任务来源携带——REST 面不收，程序化
     * 建任务（#27 回填）传入）。
     */
    public static Task publish(Long projectId, TaskType type, String title, String content,
                               Long assigneeAccountId, String waitId) {
        return new Task(projectId, type, title, content, assigneeAccountId, waitId);
    }

    /** OPC start：已发布 → 执行中（仅指派本人，应用层先过 {@link #requireAssignee}）。 */
    public void start() {
        requireTransition(TaskStatus.PUBLISHED, TaskStatus.IN_PROGRESS);
        this.status = TaskStatus.IN_PROGRESS;
    }

    /** OPC submit：执行中 → 已提交；载荷覆盖暂存，驳回字段清空（重新提交即离开驳回态）。 */
    public void submit(String payloadJson) {
        if (isBlank(payloadJson)) {
            throw new DomainException(TaskMessage.SUBMIT_PAYLOAD_INVALID);
        }
        requireTransition(TaskStatus.IN_PROGRESS, TaskStatus.SUBMITTED);
        this.status = TaskStatus.SUBMITTED;
        this.submittedPayload = payloadJson;
        this.rejectReason = null;
        this.rejectedAt = null;
    }

    /** dev confirm：已提交 → 已确认（终态）。幂等以状态机守门——非已提交即 TASK_002。 */
    public void confirm() {
        requireTransition(TaskStatus.SUBMITTED, TaskStatus.CONFIRMED);
        this.status = TaskStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    /** dev reject：已提交 → 执行中（退回重做）；reason 必填，落驳回字段。 */
    public void reject(String reason) {
        if (isBlank(reason) || reason.length() > REJECT_REASON_MAX_LENGTH) {
            throw new DomainException(TaskMessage.REJECT_REASON_REQUIRED);
        }
        requireTransition(TaskStatus.SUBMITTED, TaskStatus.IN_PROGRESS);
        this.status = TaskStatus.IN_PROGRESS;
        this.rejectReason = reason.strip();
        this.rejectedAt = LocalDateTime.now();
    }

    /** dev cancel：已发布/执行中 → 已取消。**已提交不能取消只能驳回**（docs/11 原样）。 */
    public void cancel() {
        // 已提交不能取消只能驳回（A4 §2 表）——取消源态收窄到前两态
        if (status != TaskStatus.PUBLISHED && status != TaskStatus.IN_PROGRESS) {
            throw new DomainException(TaskMessage.ILLEGAL_TRANSITION,
                    status.name(), TaskStatus.CANCELLED.name());
        }
        this.status = TaskStatus.CANCELLED;
    }

    /** 执行方守卫（A4 §2 表：start/submit 仅 assignee 本人）——非本人 TASK_004。 */
    public void requireAssignee(Long accountId) {
        if (!assigneeAccountId.equals(accountId)) {
            throw new DomainException(TaskMessage.NOT_ASSIGNEE);
        }
    }

    // ---------- 内部 ----------

    /** 迁移守卫：按 A4 §2 迁移表逐动作校验源态，非法即 TASK_002（带 from→to）。 */
    private void requireTransition(TaskStatus from, TaskStatus to) {
        if (status != from) {
            throw new DomainException(TaskMessage.ILLEGAL_TRANSITION,
                    status.name(), to.name());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean exceeds(String value, int max) {
        return value != null && value.length() > max;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
