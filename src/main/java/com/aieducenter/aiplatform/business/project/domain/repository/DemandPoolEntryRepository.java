package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.DemandPoolEntry;

/**
 * 需求池仓储（append-only：只增不改；行随项目 FK 级联删除，删除路径无显式清理）。
 */
public interface DemandPoolEntryRepository extends BaseRepository<DemandPoolEntry, Long> {

    /** 项目的收件清单（新→旧，记录时间倒序）。 */
    List<DemandPoolEntry> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);
}
