package com.aieducenter.aiplatform.base.agentengine.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.dto.response.EngineConfigResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.EngineConfig;
import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.EngineConfigRepository;

/**
 * 全局引擎配置用例（票 #42）：平台用哪个引擎由服务端统一配置（用户不选），
 * 后台查看 / 切换「当前生效引擎」。
 *
 * <p><b>生效口径 = 新项目生效、存量不迁</b>：切换只影响此后创建的项目——创建时
 * {@link #activeEngine()} 固化进项目记录；存量项目固化其创建时引擎跑完（任务下发
 * 走项目记录，不经本服务）。即时生效 = 读库不缓存，切换落库后下一次解析即新值。</p>
 *
 * <p><b>缺省链</b>：全局配置优先、无配置回落 {@link AgentEngineRegistry} 缺省
 * （opencode）。配置漂移（库值引擎已下线）同回落缺省并 warn——GET 与任务下发
 * 不因此 500，后台重切即恢复。</p>
 */
@Service
@Slf4j
public class EngineConfigAppService {

    private final EngineConfigRepository configRepository;
    private final AgentEngineRegistry registry;

    public EngineConfigAppService(EngineConfigRepository configRepository,
                                  AgentEngineRegistry registry) {
        this.configRepository = configRepository;
        this.registry = registry;
    }

    /**
     * 当前生效引擎（后台 GET 面）。
     */
    @Transactional(readOnly = true)
    public EngineConfigResponse current() {
        return new EngineConfigResponse(activeEngineName());
    }

    /**
     * 生效引擎名——创建路径等只要名字的取值面（{@link #activeEngine()} 的直读）。
     */
    @Transactional(readOnly = true)
    public String activeEngineName() {
        return activeEngine().info().name();
    }

    /**
     * 生效引擎解析——创建路径与任务下发缺省的共同入口（票 #42）：全局配置优先，
     * 无配置回落注册表缺省。
     */
    @Transactional(readOnly = true)
    public AgentEngineRegistry.RegisteredEngine activeEngine() {
        Optional<EngineConfig> config = configRepository.findById(EngineConfig.SINGLETON_ID);
        if (config.isPresent()) {
            Optional<AgentEngineRegistry.RegisteredEngine> registered =
                    registry.find(config.get().getActiveEngine());
            if (registered.isPresent()) {
                return registered.get();
            }
            log.warn("[agentengine] 全局配置引擎 {} 不在注册表（引擎已下线？），回落缺省 {}",
                    config.get().getActiveEngine(), AgentEngineRegistry.DEFAULT_ENGINE);
        }
        return registry.defaultEngine();
    }

    /**
     * 切换生效引擎（后台 PUT 面）：值须 ∈ 引擎注册表（/api/agent-engines），
     * 否则 400 AGT_009；单行 upsert——首切换落库、此后原地换值。
     *
     * @throws ApplicationException AGT_009 引擎名不在注册表（400）
     */
    @Transactional
    public EngineConfigResponse switchTo(String engine) {
        String name = registry.find(engine == null ? null : engine.trim())
                .orElseThrow(() -> new ApplicationException(AgentEngineMessage.ENGINE_CONFIG_UNKNOWN))
                .info().name();
        EngineConfig config = configRepository.findById(EngineConfig.SINGLETON_ID)
                .orElseGet(() -> EngineConfig.global(name));
        config.switchTo(name);
        configRepository.save(config);
        return new EngineConfigResponse(name);
    }
}
