package com.aieducenter.aiplatform.base.agentengine.domain.aggregate;

import cn.hutool.core.util.StrUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;

import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;

/**
 * 全局引擎配置聚合根（{@code agt_engine_config}，票 #42）：平台「当前生效引擎」的
 * 落库事实——后台切换，服务端统一配置（用户不选引擎）。
 *
 * <p><b>单例行</b>：全平台恒一行，id 钉死 {@link #SINGLETON_ID}（非 TSID）——
 * 首次切换经 {@link #global(String)} 落库，此后 {@link #switchTo(String)} 原地换值。
 * <b>无行 = 未配置</b>：读侧回落 {@code AgentEngineRegistry} 缺省（opencode），
 * 不种子——代码缺省与库值不劈叉。</p>
 *
 * <p><b>生效口径</b>：切换只影响此后创建的项目（创建时读全局配置固化进项目记录）；
 * 存量项目固化其创建时的引擎跑完，本聚合不持有也不迁任何项目级引擎。</p>
 */
@Entity
@Table(name = "agt_engine_config")
@Aggregate
@Getter
public class EngineConfig extends Auditable implements AggregateRoot<EngineConfig, Long> {

    /** 单例行 id：全平台一行的事实锚（应用侧钉死，不经 TSID）。 */
    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 生效引擎名（注册表键：opencode / dsh；入注时经注册表校验）。 */
    @Column(name = "active_engine", nullable = false, length = 50)
    private String activeEngine;

    protected EngineConfig() {
    }

    private EngineConfig(String engine) {
        if (StrUtil.isBlank(engine)) {
            throw new DomainException(AgentEngineMessage.ENGINE_CONFIG_FIELDS_INCOMPLETE);
        }
        this.id = SINGLETON_ID;
        this.activeEngine = engine;
    }

    /**
     * 首次切换落库的单例行（此前无配置 = 回落注册表缺省，不预种子）。
     */
    public static EngineConfig global(String engine) {
        return new EngineConfig(engine);
    }

    /**
     * 切换生效引擎（后台动作）：原地换值，只影响此后创建的项目。
     */
    public void switchTo(String engine) {
        if (StrUtil.isBlank(engine)) {
            throw new DomainException(AgentEngineMessage.ENGINE_CONFIG_FIELDS_INCOMPLETE);
        }
        this.activeEngine = engine;
    }
}
