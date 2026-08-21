package com.aieducenter.aiplatform.base.workspace.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;

/**
 * 工作区仓储（{@code wsp_workspaces}，中间件资源行随聚合级联增删）。
 * 聚合 ID 由应用层注册时显式赋值（workspaceId 先于 Docker 副作用存在，容器命名要用它）。
 */
public interface WorkspaceRepository extends BaseRepository<Workspace, Long> {
}
