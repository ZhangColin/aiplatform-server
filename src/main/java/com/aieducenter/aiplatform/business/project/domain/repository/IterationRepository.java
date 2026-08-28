package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;

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

    /**
     * 每项目的「当前期候选」（列表/门就绪查询收口，替代全表内存分组）：
     * OPEN 期 ∪ 无 OPEN 项目的 max-seq 闭期——{@code Iteration.currentOf} 在此
     * 子集上的选取与全量等价（OPEN 优先；有 OPEN 时闭期本就被忽略）。
     */
    @Query("""
            select i from Iteration i
            where i.status = com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus.OPEN
               or i.seq = (select max(i2.seq) from Iteration i2
                           where i2.projectId = i.projectId
                             and not exists (select i3 from Iteration i3
                                             where i3.projectId = i.projectId
                                               and i3.status = com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus.OPEN))
            """)
    List<Iteration> findCurrentPerProject();
}
