package com.aieducenter.aiplatform.business.task.domain.repository;

import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.task.domain.aggregate.Bug;
import com.aieducenter.aiplatform.business.task.domain.enums.BugStatus;

/**
 * Bug 仓储（{@code tsk_bugs}）。项目 Bug 面板 / G3 谓词（open = status ≠
 * VERIFIED，exists 精确问）/ RETEST_READY 谓词（FIXED 全量读，量小内存裁决）。
 */
public interface BugRepository extends BaseRepository<Bug, Long> {

    List<Bug> findByProjectId(Long projectId);

    List<Bug> findByStatus(BugStatus status);

    /** G3 业务谓词（A4 §5）：项目存在未关闭 Bug（≠ VERIFIED 即未关闭——含已修复待复测）。 */
    boolean existsByProjectIdAndStatusNot(Long projectId, BugStatus status);
}
