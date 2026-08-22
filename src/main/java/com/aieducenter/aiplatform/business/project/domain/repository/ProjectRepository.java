package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.Collection;
import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;

/**
 * 项目仓储。
 */
public interface ProjectRepository extends BaseRepository<Project, Long> {

    /** 一批工作区名下的项目（workbench AGENT_WAIT 投影的 workspaceId→projectId 寻址，A2 §5）。 */
    List<Project> findByWorkspaceIdIn(Collection<Long> workspaceIds);
}
