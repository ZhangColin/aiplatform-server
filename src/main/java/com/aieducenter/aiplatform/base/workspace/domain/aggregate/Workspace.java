package com.aieducenter.aiplatform.base.workspace.domain.aggregate;

import java.util.Collections;
import java.util.Set;

import cn.hutool.core.collection.CollUtil;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;

import com.aieducenter.aiplatform.base.workspace.domain.entity.MiddlewareResource;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;

/**
 * 工作区聚合根（{@code wsp_workspaces}）：环境后端句柄的持久化形态。
 *
 * <p>置备状态机（CONTEXT.md「置备状态」）：{@code PROVISIONING}（置备中，端口 0）
 * ——成功回填端口+资源→ {@code READY}；——失败级联回滚→ {@code FAILED}（带失败原因）。
 * {@code FAILED} 不可直接回 {@code READY}——需 {@link #retry()} 先回到
 * {@code PROVISIONING}（重试清空失败原因）再经 {@link #complete(WorkspaceProvision)}。
 * 注册 = 环境后端把真实副作用（容器/网络/中间件）落定后，将句柄与资源清单记录入库；
 * 销毁 = 级联清理物理资源后删除记录。生命周期与记录同生共死，不软删除
 * （Auditable 只取审计字段）。重启接回 = {@link #toHandle()} 从记录重建运行时句柄
 * （{@code PROVISIONING} 期端口为 0）。</p>
 *
 * <p>ID 显式赋值（workspaceId 先于副作用存在——容器/网络命名要用它），
 * 主键即 {@link WorkspaceId} 的数值形（TSID）。containerName / networkName 按
 * {@link WorkspaceNaming} 确定性派生（与 docker 后端同源）。</p>
 */
@Entity
@Table(name = "wsp_workspaces")
@Aggregate
@Getter
public class Workspace extends Auditable implements AggregateRoot<Workspace, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "kind", nullable = false, updatable = false)
    private EnvKind kind;

    @Column(name = "container_name", nullable = false, updatable = false)
    private String containerName;

    @Column(name = "network_name", nullable = false, updatable = false)
    private String networkName;

    @Column(name = "host_port", nullable = false)
    private int hostPort;

    @Column(name = "preview_port", nullable = false)
    private int previewPort;

    @Column(name = "provisioning_status", nullable = false)
    private ProvisioningStatus status;

    @Column(name = "provision_error")
    private String provisionError;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "workspace_id", nullable = false)
    private final Set<MiddlewareResource> resources = CollUtil.newLinkedHashSet();

    protected Workspace() {
    }

    private Workspace(WorkspaceId workspaceId, EnvKind kind, String containerName,
                      String networkName, int hostPort, int previewPort,
                      ProvisioningStatus status) {
        if (workspaceId == null || kind == null || containerName == null || containerName.isBlank()
                || networkName == null || networkName.isBlank() || status == null) {
            throw new DomainException(WorkspaceMessage.WORKSPACE_FIELDS_INCOMPLETE);
        }
        this.id = workspaceId.id();
        this.kind = kind;
        this.containerName = containerName;
        this.networkName = networkName;
        this.hostPort = hostPort;
        this.previewPort = previewPort;
        this.status = status;
    }

    /**
     * 注册 dev 工作区（环境后端 createWorkspace 落定副作用后调用，带句柄命名锚点 workspaceId）。
     */
    public static Workspace dev(WorkspaceId workspaceId, String containerName, String networkName,
                                int hostPort, int previewPort) {
        return new Workspace(workspaceId, EnvKind.DEV, containerName, networkName,
                hostPort, previewPort, ProvisioningStatus.READY);
    }

    /**
     * 注册 runtime 工作区（test/prod 纯运行占位，无端口；供给能力随后续切片落位）。
     */
    public static Workspace runtime(WorkspaceId workspaceId, EnvKind kind,
                                    String containerName, String networkName) {
        if (kind == EnvKind.DEV) {
            // 工厂误用（编程错误），非用户可触发的领域规则
            throw new IllegalArgumentException("dev 工作区走 Workspace.dev 注册");
        }
        return new Workspace(workspaceId, kind, containerName, networkName, 0, 0,
                ProvisioningStatus.READY);
    }

    /**
     * 从环境供给注册工作区（副作用落定后，句柄 + 资源清单一并入库，直接 READY）。
     */
    public static Workspace register(WorkspaceProvision provision) {
        WorkspaceHandle handle = provision.handle();
        Workspace workspace = handle.kind() == EnvKind.DEV
                ? dev(handle.workspaceId(), handle.containerName(), handle.networkName(),
                        handle.hostPort(), handle.previewPort())
                : runtime(handle.workspaceId(), handle.kind(), handle.containerName(),
                        handle.networkName());
        workspace.attachResources(provision);
        return workspace;
    }

    /**
     * 登记置备中的工作区（异步化入口）：确定性命名落位、端口 0，等待后台 docker 置备
     * 完成后经 {@link #complete(WorkspaceProvision)} 回填端口 + 资源转 READY。
     */
    public static Workspace registerPending(WorkspaceId workspaceId, EnvKind kind) {
        return new Workspace(workspaceId, kind,
                WorkspaceNaming.containerName(workspaceId, kind),
                WorkspaceNaming.networkName(workspaceId),
                0, 0, ProvisioningStatus.PROVISIONING);
    }

    /**
     * 置备完成回填（PROVISIONING → READY）：端口 + 中间件资源回填。供给的句柄必须对应
     * 本工作区（同 id / 同 kind）。{@code FAILED} 不可直接转 READY——需先 {@link #retry()}
     * 回到 PROVISIONING（#63）。
     */
    public Workspace complete(WorkspaceProvision provision) {
        if (status != ProvisioningStatus.PROVISIONING) {
            throw new DomainException(WorkspaceMessage.WORKSPACE_STATE_INVALID);
        }
        WorkspaceHandle handle = provision.handle();
        if (handle.workspaceId().id() != id || handle.kind() != kind) {
            throw new DomainException(WorkspaceMessage.WORKSPACE_STATE_INVALID);
        }
        this.hostPort = handle.hostPort();
        this.previewPort = handle.previewPort();
        attachResources(provision);
        this.status = ProvisioningStatus.READY;
        return this;
    }

    /**
     * 置备失败回滚（PROVISIONING → FAILED）：记录标记失败态并落失败原因（归一化错误码
     * + 文案，工作台可见、需要环境时阻塞的依据），物理资源回收归调用方（#57 级联回滚
     * 口径）。仅 PROVISIONING 可转 FAILED。
     */
    public Workspace markFailed(String reason) {
        if (status != ProvisioningStatus.PROVISIONING) {
            throw new DomainException(WorkspaceMessage.WORKSPACE_STATE_INVALID);
        }
        this.provisionError = reason;
        this.status = ProvisioningStatus.FAILED;
        return this;
    }

    /**
     * 重试（FAILED → PROVISIONING，#63）：置备失败的入口——回到置备中并清空失败原因，
     * 后台重新置备成功后经 {@link #complete(WorkspaceProvision)} 转 READY。仅 FAILED
     * 可重试（PROVISIONING / READY 无需也无权重试）。
     */
    public Workspace retry() {
        if (status != ProvisioningStatus.FAILED) {
            throw new DomainException(WorkspaceMessage.WORKSPACE_STATE_INVALID);
        }
        this.provisionError = null;
        this.status = ProvisioningStatus.PROVISIONING;
        return this;
    }

    /**
     * 登记随环境供给的中间件资源（幂等：同种类只留最新一条）。
     */
    public void registerResource(MiddlewareResource resource) {
        resources.remove(resource);
        resources.add(resource);
    }

    private void attachResources(WorkspaceProvision provision) {
        provision.resources().forEach(resource -> registerResource(
                new MiddlewareResource(getId(), resource.kind(),
                        resource.containerName(), resource.hostPort(), resource.internalUrl())));
    }

    public Set<MiddlewareResource> getResources() {
        return Collections.unmodifiableSet(resources);
    }

    /**
     * 中性标识（对外寻址键；主键的 TSID 形）。
     */
    public WorkspaceId workspaceId() {
        return new WorkspaceId(id);
    }

    /**
     * 从记录重建运行时句柄（服务重启后接回环境后端操作的唯一入口）。PROVISIONING 期
     * 端口为 0（置备未完成）；READY 期为真实端口。
     */
    public WorkspaceHandle toHandle() {
        return new WorkspaceHandle(workspaceId(), kind, containerName, networkName,
                hostPort, previewPort);
    }
}
