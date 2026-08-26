package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentWorkspace;
import com.aieducenter.aiplatform.base.chatagent.domain.port.PrdArtifactPort;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HarnessAgent 构建工厂（#44 建、#45 工作区分型、#48 状态落库、#49 savePrd 工具）：
 * agent 无状态（per-session 靠 RuntimeContext 寻址），同规格（name + sysPrompt +
 * model + workspace）构建一次、进程内复用；容器关闭时统一释放（HarnessAgent 是
 * AutoCloseable）。
 *
 * <p>工作区两形态（{@link ChatAgentWorkspace}）：{@link ChatAgentWorkspace.Local Local}
 * 本地目录直用（#44 既有口径）；{@link ChatAgentWorkspace.ProjectDev ProjectDev}
 * 项目 dev 工作区——经 {@code abstractFilesystem} 逃生舱换 {@link DockerExecFilesystem}
 * （docker exec 落既有 dev 容器），并关闭会写 harness 内脏进项目工作区的部件
 * （subagents / memory：源码包是交付物，记忆文件不进包；2.0.1 无 transcript 部件）
 * ——工作区上下文（AGENTS.md 等）与 workspace/tools.json 读取照常，经容器文件面
 * 即项目事实。</p>
 *
 * <p>会话状态（#48）：全形态统一接 {@link PostgresAgentStateStore}（cat_agent_state，
 * (userId, sessionId) 槽位）——平台重启后同一会话标识恢复续跑，访谈上下文不丢；
 * 替换框架缺省的本地 JSON 文件实现（单机 {@code ~/.agentscope/state/}，多副本/
 * 重启语义不成立）。</p>
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
    private final AgentStateStore stateStore;
    private final PrdArtifactPort prdArtifactPort;

    @Autowired
    public AgentscopeHarnessAgentFactory(AgentStateStore stateStore,
            PrdArtifactPort prdArtifactPort) {
        this(stateStore, prdArtifactPort, (name, sysPrompt, modelString, workspace) ->
                buildAgent(stateStore, prdArtifactPort, name, sysPrompt, modelString, workspace));
    }

    AgentscopeHarnessAgentFactory(AgentStateStore stateStore, PrdArtifactPort prdArtifactPort,
            AgentBuilder builder) {
        this.stateStore = stateStore;
        this.prdArtifactPort = prdArtifactPort;
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

    private static HarnessAgent buildAgent(AgentStateStore stateStore,
            PrdArtifactPort prdArtifactPort, String name,
            String sysPrompt, String modelString, ChatAgentWorkspace workspace) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(modelString)
                .stateStore(stateStore)
                .toolkit(interviewToolkit(workspace, prdArtifactPort));
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

    /**
     * 访谈工具集（#48 ask_user / #49 savePrd）：平台自有的对话智能体工具。
     * ask_user 无状态可共享；savePrd 锚定项目（工作区 + 业务效果经
     * {@link PrdArtifactPort}），仅项目 dev 工作区注册——本地兜底工作区无项目
     * 语境，不注册即模型不可见（BA 经编排恒带 workspaceId，不受影响）。
     */
    static Toolkit interviewToolkit(ChatAgentWorkspace workspace,
            PrdArtifactPort prdArtifactPort) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new AskUserTool());
        if (workspace instanceof ChatAgentWorkspace.ProjectDev dev) {
            toolkit.registerAgentTool(new SavePrdTool(prdArtifactPort.workspacePath(),
                    dev.workspaceId(), dev.containerName(), prdArtifactPort));
        }
        return toolkit;
    }
}
