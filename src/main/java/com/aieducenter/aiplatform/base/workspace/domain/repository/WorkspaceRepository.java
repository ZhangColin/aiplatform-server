package com.aieducenter.aiplatform.base.workspace.domain.repository;

import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;

/**
 * 工作区仓储（{@code wsp_workspaces}，中间件资源行随聚合级联增删）。
 * 聚合 ID 由应用层注册时显式赋值（workspaceId 先于 Docker 副作用存在，容器命名要用它）。
 */
public interface WorkspaceRepository extends BaseRepository<Workspace, Long> {

    /** 按置备状态查（workbench 置备失败待办投影源：failed 态清单）。 */
    List<Workspace> findByStatus(ProvisioningStatus status);
}
