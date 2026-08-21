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

import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * 项目聚合根（{@code prj_projects}）：用户的长期实体——业务字段 + dev 工作区引用
 * + 归属账号 + 归档终点（A3 §4）。
 *
 * <p>状态机主体在期（{@code prj_iterations}），项目自身无阶段字段；「开发中/
 * 已交付」是有无 OPEN 期的派生投影，归档是单向终点动作（archived_at 落定，
 * 端点归片5c）。Phase A 一个项目 = 一个 dev 环境（workspaceId 软引用 wsp 表，
 * 级联清理由编排负责）。删除真删级联，无软删除——继承 Auditable 只取审计字段。</p>
 */
@Entity
@Table(name = "prj_projects")
@Aggregate
@Getter
public class Project extends Auditable implements AggregateRoot<Project, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "type", nullable = false, updatable = false)
    private ProjectType type;

    @Column(name = "engine", nullable = false, updatable = false, length = 20)
    private String engine;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 归属账号（A2：创建时填、v1 不过滤；测试/无会话上下文可空）。 */
    @Column(name = "owner_account_id", updatable = false)
    private Long ownerAccountId;

    /** 归档时间（单向终点；NULL = 未归档，归档动作归片5c）。 */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    protected Project() {
    }

    private Project(String name, ProjectType type, String engine, Long workspaceId,
                    Long ownerAccountId) {
        if (name == null || name.isBlank()) {
            throw new DomainException(ProjectMessage.PROJECT_NAME_BLANK);
        }
        if (engine == null || engine.isBlank()) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        if (workspaceId == null) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        this.name = name;
        this.type = ProjectType.orDefault(type);
        this.engine = engine;
        this.workspaceId = workspaceId;
        this.ownerAccountId = ownerAccountId;
    }

    /**
     * 建项目（编排在工作区副作用落定后调用，一事务与第 1 期同建）。
     */
    public static Project create(String name, ProjectType type, String engine,
                                 Long workspaceId, Long ownerAccountId) {
        return new Project(name, type, engine, workspaceId, ownerAccountId);
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
