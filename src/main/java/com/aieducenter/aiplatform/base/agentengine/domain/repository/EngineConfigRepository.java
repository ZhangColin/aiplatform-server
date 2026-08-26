package com.aieducenter.aiplatform.base.agentengine.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.EngineConfig;

/**
 * 全局引擎配置仓储：单例行 {@link EngineConfig#SINGLETON_ID} 的读写面
 * （findById 即全表语义，无第二行）。
 */
public interface EngineConfigRepository extends BaseRepository<EngineConfig, Long> {
}
