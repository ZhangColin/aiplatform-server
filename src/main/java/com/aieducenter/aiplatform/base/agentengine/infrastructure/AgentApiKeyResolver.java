package com.aieducenter.aiplatform.base.agentengine.infrastructure;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 引擎 API Key 解析（适配层内部机制，不进口子清单）：按 {@link AgentModelConfig}
 * 的 provider 名约定环境变量 {@code <PROVIDER>_API_KEY}（deepseek →
 * DEEPSEEK_API_KEY）。Key 只经环境注入容器内引擎进程，不进镜像、不进 git、不落库。
 *
 * <p>缺 key 不阻断启动（引擎 serve/CLI 照常可用），首条消息才以引擎侧鉴权错误暴露
 * ——与 demo 行为一致（「任务时给出明确报错」）。</p>
 */
@Component
@Slf4j
public class AgentApiKeyResolver {

    private final AgentModelConfig modelConfig;

    public AgentApiKeyResolver(AgentModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    /** 环境变量名（provider 约定派生）。 */
    public String envName() {
        return modelConfig.provider().toUpperCase().replace('-', '_') + "_API_KEY";
    }

    /**
     * 解析 API Key；未配置返回 null（引擎调用时以真实错误暴露，不在此造空值）。
     */
    public String resolve() {
        String key = System.getenv(envName());
        if (key == null || key.isBlank()) {
            log.warn("未设置 {}——引擎消息调用将因鉴权失败（开发智能体 key 只经环境注入）", envName());
            return null;
        }
        return key;
    }

    /**
     * shell 命令前缀（环境注入形态，适配器 exec 拼接用）：无 key = 空串，
     * 有 key = {@code ENV='key' }（引号包裹，前缀以空格结尾可直接拼接）。
     */
    public String envPrefix() {
        String key = resolve();
        return key == null ? "" : envName() + "='" + key + "' ";
    }
}
