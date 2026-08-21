package com.aieducenter.aiplatform.base.agentengine.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型默认值配置（A1 §2.3：base.agentengine 配置类，provider/model 默认值，填入
 * UsageEvent 与引擎请求体；不进四口子清单）。任务下发的 modelId 入参缺省时以本配置
 * 兜底——适配层仍零角色概念（角色档位是调用方的事）。
 */
@Component
public class AgentModelConfig {

    private final String provider;
    private final String model;

    public AgentModelConfig(
            @Value("${app.agent.model.provider:deepseek}") String provider,
            @Value("${app.agent.model.id:deepseek-v4-pro}") String model) {
        this.provider = provider;
        this.model = model;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    /**
     * 任务入参的模型档位兜底：modelId 空/空白取默认模型（适配层零角色概念，
     * 调用方不给档位时才落到平台默认）。
     */
    public String resolve(String modelId) {
        return modelId == null || modelId.isBlank() ? model : modelId;
    }
}
