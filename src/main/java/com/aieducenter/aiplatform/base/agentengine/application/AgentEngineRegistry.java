package com.aieducenter.aiplatform.base.agentengine.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.port.CodingAgentAdapter;

/**
 * 引擎注册表（B0 蓝图 §2 片2：显式注册，不靠 Spring bean 名）：收集全部
 * {@link CodingAgentAdapter} 实现，按 {@code adapter.engine()}（实现自述的名字，
 * 非 bean 名）建索引。换引擎 / 加引擎 = 实现端口 + 落为 bean，注册表与前端选项
 * 自动出现——能力矩阵同样由适配器自述（A1 §1.5 如实暴露，行为与矩阵不劈叉）。
 *
 * <p>启动即校验：engine() 重名 fail-fast（显式注册的唯一性守卫）。</p>
 */
@Component
public class AgentEngineRegistry {

    /** 缺省引擎（任务下发未指定时）：平台配置事实，非注册顺序的偶然。 */
    public static final String DEFAULT_ENGINE = "opencode";

    /** 引擎能力与适配器的登记项。 */
    public record RegisteredEngine(EngineInfo info, CodingAgentAdapter adapter) {
    }

    /** 能力矩阵行（REST 面直接透出）。 */
    public record EngineInfo(String name, String label,
                             boolean questionSupported, boolean permissionSupported, String note) {
    }

    private final Map<String, RegisteredEngine> engines = new LinkedHashMap<>();

    public AgentEngineRegistry(List<CodingAgentAdapter> adapters) {
        for (CodingAgentAdapter adapter : adapters) {
            RegisteredEngine previous = engines.put(adapter.engine(), register(adapter));
            if (previous != null) {
                throw new IllegalStateException("开发智能体引擎重名注册: " + adapter.engine());
            }
        }
    }

    private RegisteredEngine register(CodingAgentAdapter adapter) {
        return new RegisteredEngine(new EngineInfo(
                adapter.engine(), adapter.label(),
                adapter.supportsQuestions(), adapter.supportsPermissions(), adapter.note()),
                adapter);
    }

    /**
     * 能力矩阵（全部已登记引擎）。
     */
    public List<EngineInfo> matrix() {
        return List.copyOf(engines.values().stream().map(RegisteredEngine::info).toList());
    }

    /**
     * 按名查引擎；未登记返回空（查询面，不抛——配置校验/缺省解析用）。
     */
    public Optional<RegisteredEngine> find(String engine) {
        return engine == null ? Optional.empty() : Optional.ofNullable(engines.get(engine));
    }

    /**
     * 按名取引擎；未登记抛 AGT_001（404）。
     */
    public RegisteredEngine require(String engine) {
        return find(engine).orElseThrow(() -> new ApplicationException(AgentEngineMessage.ENGINE_NOT_FOUND));
    }

    /**
     * 缺省引擎（任务下发未指定时）：平台配置事实（opencode）。
     */
    public RegisteredEngine defaultEngine() {
        return require(DEFAULT_ENGINE);
    }
}
