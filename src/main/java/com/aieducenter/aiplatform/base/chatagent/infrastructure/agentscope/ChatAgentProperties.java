package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话智能体配置（#44，前缀 app.chatagent）：模型默认值 / 默认人格 / agent 名 /
 * 工作区根 / 单轮超时。workspace 是本地兜底工作区（#45：命令未带 workspaceId 时用），
 * 为空时用 AgentScope 默认（.agentscope/workspace）；带 workspaceId 的对话解析为
 * 项目 dev 工作区（容器文件面，不经此配置）。
 */
@Component
@ConfigurationProperties(prefix = "app.chatagent")
public class ChatAgentProperties {

    /** agent 名（AgentState 落盘命名空间的一部分，稳定勿动） */
    private String agentName = "chat-agent";

    /** 默认模型串（provider:modelId，白名单见 ModelRef） */
    private String defaultModel = "deepseek:deepseek-v4-flash";

    /** 默认系统提示词（命令未带 systemPrompt 时兜底） */
    private String defaultSystemPrompt = "你是平台对话智能体。";

    /** 工作区根路径（可空 → AgentScope 默认） */
    private Path workspace;

    /** 单轮对话超时 */
    private Duration timeout = Duration.ofMinutes(2);

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String getDefaultSystemPrompt() {
        return defaultSystemPrompt;
    }

    public void setDefaultSystemPrompt(String defaultSystemPrompt) {
        this.defaultSystemPrompt = defaultSystemPrompt;
    }

    public Path getWorkspace() {
        return workspace;
    }

    public void setWorkspace(Path workspace) {
        this.workspace = workspace;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
