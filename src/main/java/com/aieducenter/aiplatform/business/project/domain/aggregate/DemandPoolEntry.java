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
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.business.project.domain.enums.DemandEntryKind;
import com.aieducenter.aiplatform.business.project.domain.enums.DemandSource;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * 需求池条目（{@code prj_demand_pool_entries}，A3 §4）：项目级、随时可记的
 * 需求/bug 收件清单——append-only 只增不改，无状态列（「记录不等同开工」，
 * CONTEXT「需求池」词条）；开新期时作为需求梳理输入。
 *
 * <p>验收前后都能记、用户/测试都能提（工具与过程正交：期关了照常记）；驳回
 * 反馈不自动入池——入池是显式动作。{@code kind} 可空（收件时不强分类）；
 * BUG 类型条目是收件记录不是缺陷实体（缺陷三态走 A4 Bug 系统）。{@code createdAt}
 * 在记录时取定（业务时间，非审计时间——列表排序锚点）；行随项目 FK 级联删除。
 * 刻意不继承 Auditable：本表的 created_at/created_by 就是业务字段本身（记录
 * 时间/记录账号），append-only 收件记录无审计列可加——与期/确认表（业务时间
 * 另名、审计列保留）的取舍相反，各自贴各自的事实。</p>
 */
@Entity
@Table(name = "prj_demand_pool_entries")
@Aggregate
@Getter
public class DemandPoolEntry implements AggregateRoot<DemandPoolEntry, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 收件内容（需求描述或缺陷反馈原文）。 */
    @Column(name = "content", nullable = false, updatable = false, length = 2000)
    private String content;

    /** 条目类型（可空——不强分类；BUG 是收件记录不是缺陷实体）。 */
    @Column(name = "kind", updatable = false)
    private DemandEntryKind kind;

    /** 来源（用户/测试/验收；缺省用户，缺省值由应用层在入池前补齐）。 */
    @Column(name = "source", nullable = false, updatable = false)
    private DemandSource source;

    /** 记录账号（idn_accounts 软引用；无会话上下文可空）。 */
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    /** 记录时间（业务时间，非审计时间——收件清单的排序锚点）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected DemandPoolEntry() {
    }

    private DemandPoolEntry(Long projectId, String content, DemandEntryKind kind,
                            DemandSource source, Long createdBy) {
        if (projectId == null || source == null) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        if (content == null || content.isBlank()) {
            throw new DomainException(ProjectMessage.DEMAND_CONTENT_BLANK);
        }
        this.projectId = projectId;
        this.content = content.strip();
        this.kind = kind;
        this.source = source;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 入池（随时可记：验收前后、期开期关都能记，A3 §4）。
     */
    public static DemandPoolEntry entryOf(Long projectId, String content, DemandEntryKind kind,
                                          DemandSource source, Long createdBy) {
        return new DemandPoolEntry(projectId, content, kind, source, createdBy);
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
