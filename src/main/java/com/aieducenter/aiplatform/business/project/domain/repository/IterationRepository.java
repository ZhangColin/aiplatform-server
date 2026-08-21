package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.List;
import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;

/**
 * 期仓储（OPEN 期寻址：每项目至多一个，库侧部分唯一索引兜底）。
 */
public interface IterationRepository extends BaseRepository<Iteration, Long> {

    Optional<Iteration> findByProjectIdAndStatus(Long projectId, IterationStatus status);

    List<Iteration> findByStatus(IterationStatus status);

    List<Iteration> findByProjectId(Long projectId);

    void deleteByProjectId(Long projectId);
}
