package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;

/**
 * 项目仓储。
 */
public interface ProjectRepository extends BaseRepository<Project, Long> {

    /** 一批工作区名下的项目（workbench AGENT_WAIT 投影的 workspaceId→projectId 寻址，A2 §5）。 */
    List<Project> findByWorkspaceIdIn(Collection<Long> workspaceIds);

    /** 工作区名下的项目（Phase A 一项目一 dev 环境；savePrd 落盘回调按工作区寻址项目，#49）。 */
    Optional<Project> findByWorkspaceId(Long workspaceId);
}
