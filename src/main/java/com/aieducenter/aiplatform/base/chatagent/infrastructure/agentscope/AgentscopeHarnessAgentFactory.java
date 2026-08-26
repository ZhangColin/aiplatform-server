package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentWorkspace;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * HarnessAgent 构建工厂（#44 建、#45 工作区分型）：agent 无状态（per-session 靠
 * RuntimeContext 寻址），同规格（name + sysPrompt + model + workspace）构建一次、
 * 进程内复用；容器关闭时统一释放（HarnessAgent 是 AutoCloseable）。
 *
 * <p>工作区两形态（{@link ChatAgentWorkspace}）：{@link ChatAgentWorkspace.Local Local}
 * 本地目录直用（#44 既有口径）；{@link ChatAgentWorkspace.ProjectDev ProjectDev}
 * 项目 dev 工作区——经 {@code abstractFilesystem} 逃生舱换 {@link DockerExecFilesystem}
 * （docker exec 落既有 dev 容器），并关闭会写 harness 内脏进项目工作区的部件
 * （subagents / memory：源码包是交付物，记忆文件不进包；会话恢复归 #48；2.0.1
 * 无 transcript 部件）——工作区上下文（AGENTS.md 等）与 workspace/tools.json
 * 读取照常，经容器文件面即项目事实。
 */
@Slf4j
@Component
public class AgentscopeHarnessAgentFactory implements DisposableBean {

    /**
     * 真正构建 HarnessAgent 的步骤（抽出便于单测注入替身）。
     */
    interface AgentBuilder {

        HarnessAgent build(String name, String sysPrompt, String modelString,
                ChatAgentWorkspace workspace);
    }

    private final ConcurrentHashMap<String, HarnessAgent> agents = new ConcurrentHashMap<>();
    private final AgentBuilder builder;

    public AgentscopeHarnessAgentFactory() {
        this(AgentscopeHarnessAgentFactory::buildAgent);
    }

    AgentscopeHarnessAgentFactory(AgentBuilder builder) {
        this.builder = builder;
    }

    public HarnessAgent obtain(String name, String sysPrompt, String modelString,
            ChatAgentWorkspace workspace) {
        // sysPrompt/workspace 明文入键（不用 hashCode：碰撞会把不同人格/工作区的
        // agent 当同规格静默复用）
        String key = name + "|" + modelString + "|" + sysPrompt + "|" + workspace.identity();
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
            ChatAgentWorkspace workspace) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(modelString);
        switch (workspace) {
            case ChatAgentWorkspace.Local local -> {
                if (local.root() != null) {
                    builder.workspace(local.root());
                }
            }
            case ChatAgentWorkspace.ProjectDev dev -> builder
                    // 名义根：与容器内工作区根同形（路径规范化剥前缀后即工作区锚定形）
                    .workspace(java.nio.file.Path.of(ChatAgentWorkspace.ProjectDev.CONTAINER_ROOT))
                    .abstractFilesystem(new DockerExecFilesystem(dev.containerName()))
                    .disableSubagents()
                    .disableMemoryHooks()
                    .disableMemoryTools();
        }
        return builder.build();
    }
}
