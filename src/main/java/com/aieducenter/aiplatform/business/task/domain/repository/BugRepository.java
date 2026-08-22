package com.aieducenter.aiplatform.business.task.domain.repository;

import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.task.domain.aggregate.Bug;
import com.aieducenter.aiplatform.business.task.domain.enums.BugStatus;

/**
 * Bug 仓储（{@code tsk_bugs}）。项目 Bug 面板 / G3 谓词（open = status ≠
 * VERIFIED，exists 精确问）/ RETEST_READY 谓词（FIXED 全量读，量小内存裁决）/
 * 修复链派发面（OPEN ∧ fix_run_id 判定，#27）。
 */
public interface BugRepository extends BaseRepository<Bug, Long> {

    List<Bug> findByProjectId(Long projectId);

    List<Bug> findByStatus(BugStatus status);

    /** G3 业务谓词（A4 §5）：项目存在未关闭 Bug（≠ VERIFIED 即未关闭——含已修复待复测）。 */
    boolean existsByProjectIdAndStatusNot(Long projectId, BugStatus status);

    /** 可派发池快照（#27 链起点，旧→新稳定序）。 */
    List<Bug> findByProjectIdAndStatusAndFixRunIdIsNullOrderByCreatedAtAsc(
            Long projectId, BugStatus status);

    /** in-flight 判定（A4 §4 派发幂等门）：存在 OPEN ∧ fix_run_id 非空的 Bug。 */
    boolean existsByProjectIdAndStatusAndFixRunIdIsNotNull(Long projectId, BugStatus status);

    /** 孤儿修复 run 扫描（#27 重启恢复）：OPEN ∧ fix_run_id 非空即链必已死。 */
    List<Bug> findByStatusAndFixRunIdIsNotNull(BugStatus status);
}
