package com.aieducenter.aiplatform.base.workspace.domain.entity;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import com.cartisan.core.domain.DomainEntity;
import com.cartisan.core.exception.DomainException;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;

/**
 * 中间件资源（Workspace 聚合成员，{@code wsp_resources}）：一工作区一 pg 一 redis，
 * 随聚合创建与级联删除。internalUrl 为容器网络内连接串（含凭据），
 * 即 {@code /workspace/.env} 注入的原文——落库是为了重启接回后连接信息可查。
 */
@Entity
@Table(name = "wsp_resources", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"workspace_id", "kind"})})
@Getter
public class MiddlewareResource implements DomainEntity<MiddlewareResource, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    // workspace_id 由聚合侧 @OneToMany @JoinColumn 持有写入权（单向关联的去重映射约定），
    // 此字段只读——业务键身份与资源行定位用
    @Column(name = "workspace_id", nullable = false, insertable = false, updatable = false)
    private Long workspaceId;

    @Column(name = "kind", nullable = false, updatable = false)
    private MiddlewareKind kind;

    @Column(name = "container_name", nullable = false, updatable = false)
    private String containerName;

    @Column(name = "host_port", nullable = false, updatable = false)
    private int hostPort;

    @Column(name = "internal_url", nullable = false, updatable = false)
    private String internalUrl;

    protected MiddlewareResource() {
    }

    public MiddlewareResource(Long workspaceId, MiddlewareKind kind, String containerName,
                              int hostPort, String internalUrl) {
        if (workspaceId == null || kind == null || containerName == null || containerName.isBlank()
                || internalUrl == null || internalUrl.isBlank()) {
            throw new DomainException(WorkspaceMessage.RESOURCE_FIELDS_INCOMPLETE);
        }
        this.workspaceId = workspaceId;
        this.kind = kind;
        this.containerName = containerName;
        this.hostPort = hostPort;
        this.internalUrl = internalUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MiddlewareResource that = (MiddlewareResource) o;
        // 业务键：一工作区一资源种类（代理主键不作身份）
        return Objects.equals(workspaceId, that.workspaceId) && kind == that.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceId, kind);
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = TsidGenerator.newInstance().generate();
        }
    }
}
