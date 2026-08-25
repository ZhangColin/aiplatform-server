package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * HarnessAgent 构建工厂（#44）：agent 无状态（per-session 靠 RuntimeContext 寻址），
 * 同规格（name + sysPrompt + model + workspace）构建一次、进程内复用；容器关闭时统一
 * 释放（HarnessAgent 是 AutoCloseable）。
 */
@Slf4j
@Component
public class AgentscopeHarnessAgentFactory implements DisposableBean {

    /**
     * 真正构建 HarnessAgent 的步骤（抽出便于单测注入替身）。
     */
    interface AgentBuilder {

        HarnessAgent build(String name, String sysPrompt, String modelString, Path workspace);
    }

    private final ConcurrentHashMap<String, HarnessAgent> agents = new ConcurrentHashMap<>();
    private final AgentBuilder builder;

    public AgentscopeHarnessAgentFactory() {
        this(AgentscopeHarnessAgentFactory::buildAgent);
    }

    AgentscopeHarnessAgentFactory(AgentBuilder builder) {
        this.builder = builder;
    }

    public HarnessAgent obtain(String name, String sysPrompt, String modelString, Path workspace) {
        // sysPrompt 明文入键（不用 hashCode：碰撞会把不同人格的 agent 当同规格静默复用）
        String key = name + "|" + modelString + "|" + sysPrompt + "|" + workspace;
        return agents.computeIfAbsent(key,
                k -> builder.build(name, sysPrompt, modelString, workspace));
    }

    @Override
    public void destroy() {
        agents.values().forEach(agent -> {
            try {
                agent.close();
            }
            catch (Exception e) {
                log.warn("关闭 HarnessAgent 失败（忽略，继续关闭其余实例）", e);
            }
        });
        agents.clear();
    }

    private static HarnessAgent buildAgent(String name, String sysPrompt, String modelString,
            Path workspace) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(modelString);
        if (workspace != null) {
            builder.workspace(workspace);
        }
        return builder.build();
    }
}
