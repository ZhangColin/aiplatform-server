package com.aieducenter.aiplatform.business.project.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.Confirmation;

/**
 * 确认记录仓储（append-only：只增不改，无更新语义的用例；行随期 FK 级联删除）。
 * 留痕时间线查询面（验收审计/纪要回溯）随首个消费方（A5 纪要）再加。
 */
public interface ConfirmationRepository extends BaseRepository<Confirmation, Long> {
}
