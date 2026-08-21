package com.aieducenter.aiplatform.business.project.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;

/**
 * 项目仓储。
 */
public interface ProjectRepository extends BaseRepository<Project, Long> {
}
