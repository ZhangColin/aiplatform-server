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

    /** in-flight 面（A4 §4 派发幂等门）：OPEN ∧ fix_run_id 非空——新鲜在飞空转、
     * 陈旧回收续链（年龄判定在应用层，#36）。 */
    List<Bug> findByProjectIdAndStatusAndFixRunIdIsNotNull(Long projectId, BugStatus status);

    /** 孤儿修复 run 扫描（#27 重启恢复）：OPEN ∧ fix_run_id 非空，陈旧与否由
     * 调用方按宽限裁决（清走在飞标记即守卫被拒→重复修复，#36）。 */
    List<Bug> findByStatusAndFixRunIdIsNotNull(BugStatus status);
}
