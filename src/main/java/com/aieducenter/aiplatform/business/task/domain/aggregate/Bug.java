package com.aieducenter.aiplatform.business.task.domain.aggregate;

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

import com.aieducenter.aiplatform.business.task.domain.enums.BugSeverity;
import com.aieducenter.aiplatform.business.task.domain.enums.BugStatus;
import com.aieducenter.aiplatform.business.task.domain.error.TaskMessage;

/**
 * Bug 聚合根（{@code tsk_bugs}，A4 §1/§3）：测试确认时刻入库（OPEN）的项目级
 * Bug 记录，与过程正交（期后修复不锁）。三态 OPEN 待修复 / FIXED 已修复
 * （修复链乐观翻转，#27）/ VERIFIED 复测通过——**唯一关闭态**（手工关闭是其
 * 带理由的别名动作，端点随 #27）。修复派发字段（fixRunId/fixNote）随 #27 落
 * 读写，本票建列先就位。
 *
 * <p>{@code attachments} 留缝（URL 数组 jsonb，v1 无上传通道恒 NULL）。不软删除：
 * 关闭态即历史（项目真删级联清行）。</p>
 */
@Entity
@Table(name = "tsk_bugs")
@Aggregate
@Getter
public class Bug extends Auditable implements AggregateRoot<Bug, Long> {

    /** 与库列宽对齐（V11 迁移）。 */
    public static final int TITLE_MAX_LENGTH = 200;
    public static final int CLOSED_REASON_MAX_LENGTH = 1000;
    public static final int FIX_RUN_ID_MAX_LENGTH = 100;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 来源测试任务（确认时入库的溯源键）。 */
    @Column(name = "source_task_id", nullable = false, updatable = false)
    private Long sourceTaskId;

    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "repro_steps", columnDefinition = "text")
    private String reproSteps;

    @Column(name = "severity", nullable = false, updatable = false)
    private BugSeverity severity;

    /** URL 数组留缝（v1 无上传通道，恒 NULL）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments", columnDefinition = "jsonb", updatable = false)
    private String attachments;

    @Column(name = "status", nullable = false)
    private BugStatus status;

    /** 修复 run 引用（#27 修复链；in-flight 判定 = 非空 ∧ OPEN）。 */
    @Column(name = "fix_run_id", length = FIX_RUN_ID_MAX_LENGTH)
    private String fixRunId;

    /** 修复 run 最终消息（「未重现」等结论如实记，#27 链收终态时落）。 */
    @Column(name = "fix_note", columnDefinition = "text")
    private String fixNote;

    /** 手工关闭理由（bogus Bug，端点随 #27）。 */
    @Column(name = "closed_reason", length = CLOSED_REASON_MAX_LENGTH)
    private String closedReason;

    protected Bug() {
    }

    private Bug(Long projectId, Long sourceTaskId, String title, String description,
                String reproSteps, BugSeverity severity) {
        if (projectId == null || sourceTaskId == null || severity == null
                || isBlank(title) || title.length() > TITLE_MAX_LENGTH) {
            throw new DomainException(TaskMessage.TASK_FIELDS_INCOMPLETE);
        }
        this.projectId = projectId;
        this.sourceTaskId = sourceTaskId;
        this.title = title.strip();
        this.description = description == null ? null : description.strip();
        this.reproSteps = reproSteps == null ? null : reproSteps.strip();
        this.severity = severity;
        this.status = BugStatus.OPEN;
    }

    /**
     * 首轮测试确认时入库（A4 §3：确认前不动——驳回/取消路径天然干净）。
     */
    public static Bug openOf(Long projectId, Long sourceTaskId, String title,
                             String description, String reproSteps, BugSeverity severity) {
        return new Bug(projectId, sourceTaskId, title, description, reproSteps, severity);
    }

    /**
     * 修复 run 正常收尾的乐观翻转（#27 修复链入口；本票测试夹具与谓词先就位）：
     * OPEN → FIXED，记 fixRunId/fixNote。真伪由复测裁决——三态里复测本就是兜底。
     */
    public static Bug fixedOf(Long projectId, Long sourceTaskId, String title,
                              String fixRunId, String fixNote) {
        Bug bug = new Bug(projectId, sourceTaskId, title, null, null, BugSeverity.MAJOR);
        bug.status = BugStatus.FIXED;
        bug.fixRunId = fixRunId;
        bug.fixNote = fixNote;
        return bug;
    }

    /**
     * 复测结果翻态（A4 §3）：pass=true → VERIFIED（唯一关闭态）；pass=false →
     * 退回 OPEN（清修复派发字段——退回即回可派发池，in-flight 判定归零，#27）。
     * VERIFIED 是终态——再翻即 TASK_002。
     */
    public void applyRetestResult(boolean pass) {
        if (status == BugStatus.VERIFIED) {
            throw new DomainException(TaskMessage.ILLEGAL_TRANSITION,
                    status.name(), pass ? BugStatus.VERIFIED.name() : BugStatus.OPEN.name());
        }
        this.status = pass ? BugStatus.VERIFIED : BugStatus.OPEN;
        if (!pass) {
            this.fixRunId = null; // 旧 run 引用作废（新修复 run 落新引用/新结论）
            this.fixNote = null;
        }
    }

    /**
     * 修复 run 派发标记（#27 链）：in-flight 判定 = OPEN ∧ fixRunId 非空。
     * 仅 OPEN ∧ 未标记可派（幂等门），否则 TASK_002——并发/重复派发的守门。
     */
    public void markFixDispatched(String runId) {
        if (status != BugStatus.OPEN || fixRunId != null
                || runId == null || runId.isBlank()) {
            throw new DomainException(TaskMessage.ILLEGAL_TRANSITION,
                    status.name(), BugStatus.FIXED.name());
        }
        this.fixRunId = runId.strip();
    }

    /**
     * 修复 run 正常收尾的乐观翻转（#27 链 sink 收终态）：OPEN → FIXED + 记
     * fixRunId/fixNote（run 最终消息——「未重现」等结论如实记）。非 OPEN 或
     * runId 不匹配（复测已裁决/手工已关闭/标记已被替换）返回 false——迟到的
     * 终态不覆盖后来状态。真伪由复测裁决——三态里复测本就是兜底。
     */
    public boolean markFixed(String runId, String fixNote) {
        if (status != BugStatus.OPEN || !runId.equals(this.fixRunId)) {
            return false;
        }
        this.status = BugStatus.FIXED;
        this.fixNote = fixNote == null ? null : fixNote.strip();
        return true;
    }

    /**
     * 修复 run 失败/孤儿回收（#27 链 error/timeout 与重启恢复）：fixRunId 匹配
     * ∧ OPEN 才清（迟到终态不清别人的标记）——fixRunId 置 NULL 回可派发池。
     * 返回是否清理（false = 无事可做）。
     */
    public boolean abandonFixRun(String runId) {
        if (status != BugStatus.OPEN || !runId.equals(this.fixRunId)) {
            return false;
        }
        this.fixRunId = null;
        return true;
    }

    /**
     * bogus 手工关闭（A4 §4，#27）：OPEN/FIXED → VERIFIED + closedReason——
     * 复测通过这一唯一关闭态的带理由别名动作，**不加第四态**（G3 谓词不变）；
     * VERIFIED 终态再关即 TASK_002。修复派发字段不动（审计留痕）。
     */
    public void closeManually(String reason) {
        if (reason == null || reason.isBlank()
                || reason.length() > CLOSED_REASON_MAX_LENGTH) {
            throw new DomainException(TaskMessage.BUG_CLOSE_REASON_REQUIRED);
        }
        if (status == BugStatus.VERIFIED) {
            throw new DomainException(TaskMessage.ILLEGAL_TRANSITION,
                    status.name(), BugStatus.VERIFIED.name());
        }
        this.status = BugStatus.VERIFIED;
        this.closedReason = reason.strip();
    }

    // ---------- 内部 ----------

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
