package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentWorkspace;
import com.aieducenter.aiplatform.base.chatagent.domain.port.PrdArtifactPort;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentscopeHarnessAgentFactory} 实例缓存与生命周期（#44：HarnessAgent
 * 无状态可复用，per-session 靠 RuntimeContext，同规格构建恰一次；#45：工作区
 * 身份入规格键——不同容器不复用、同容器不同形态不复用；#48：stateStore 注入
 * 构建缝——生产为 PG 版，测试用内存替身）。
 */
class AgentscopeHarnessAgentFactoryTest {

    /** PRD 业务契约桩（#49：路径正本 + 落盘回调空转——工厂只取路径，效果归适配器测试）。 */
    private static final PrdArtifactPort PRD_PORT = new PrdArtifactPort() {
        @Override
        public String workspacePath() {
            return "docs/PRD.md";
        }

        @Override
        public void onWritten(String workspaceId) {
            // 空转：工厂测试不触达落盘回调
        }
    };

    private AgentscopeHarnessAgentFactory factoryWith(List<HarnessAgent> created) {
        return factoryWith(created, new InMemoryAgentStateStore());
    }

    private AgentscopeHarnessAgentFactory factoryWith(List<HarnessAgent> created,
                                                      AgentStateStore stateStore) {
        return new AgentscopeHarnessAgentFactory(stateStore, PRD_PORT,
                (name, sysPrompt, modelString, workspace) -> {
                    HarnessAgent agent = mock(HarnessAgent.class);
                    created.add(agent);
                    return agent;
                });
    }

    @Test
    void given_same_spec_when_obtain_twice_then_built_once_and_reused() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        HarnessAgent first = factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.Local(null));
        HarnessAgent second = factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.Local(null));

        assertThat(second).isSameAs(first);
        assertThat(created).hasSize(1);
    }

    @Test
    void given_different_spec_when_obtain_then_new_instance_per_spec() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.Local(null));
        factory.obtain("chat-agent", "另一个 sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.Local(null));
        factory.obtain("chat-agent", "sys", "deepseek:deepseek-chat",
                new ChatAgentWorkspace.Local(null));
        factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.Local(java.nio.file.Path.of("/tmp/other-workspace")));
        factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.ProjectDev("1", "ws-1-dev"));
        factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.ProjectDev("2", "ws-2-dev"));

        assertThat(created).hasSize(6);
    }

    @Test
    void given_workspace_identity_when_obtain_then_keyed_by_container() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        HarnessAgent first = factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.ProjectDev("42", "ws-42-dev"));
        // 同 workspaceId 同容器 = 同规格（复用）；同 id 不同容器名 = 不同规格
        HarnessAgent same = factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.ProjectDev("42", "ws-42-dev"));

        assertThat(same).isSameAs(first);
        assertThat(created).hasSize(1);
    }

    @Test
    void given_local_vs_project_dev_when_obtain_then_not_shared() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.Local(null));
        factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.ProjectDev("42", "ws-42-dev"));

        assertThat(created).hasSize(2);
    }

    @Test
    void given_cached_agents_when_destroy_then_all_closed_and_cache_cleared() throws IOException {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);
        factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.Local(null));
        factory.obtain("chat-agent", "sys2", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.Local(null));

        factory.destroy();

        for (HarnessAgent agent : created) {
            verify(agent, times(1)).close();
        }
        factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                new ChatAgentWorkspace.Local(null));
        assertThat(created).hasSize(3);
    }

    @Test
    void given_state_store_when_built_then_wired_into_agent() {
        // 真构建路径（不走替身 builder）：#48 会话恢复的落点——agent 持有的是
        // 注入的 store（生产为 PG 版），非框架缺省的本地 JSON 文件实现。
        // builder().model() 即解析模型串（需 API key），无 key 环境跳过（冒烟同款口径）
        assumeTrue(System.getenv("DEEPSEEK_API_KEY") != null,
                "无 DEEPSEEK_API_KEY，跳过真构建断言");
        AgentStateStore stateStore = new InMemoryAgentStateStore();
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(stateStore, PRD_PORT);

        HarnessAgent agent = factory.obtain("chat-agent-t", "sys",
                "deepseek:deepseek-v4-flash", new ChatAgentWorkspace.Local(null));

        assertThat(agent.getStateStore()).isSameAs(stateStore);
    }

    @Test
    void given_local_vs_project_dev_when_interview_toolkit_then_savePrd_only_on_project_dev() {
        // #49：savePrd 锚定项目（工作区 + 业务效果经端口）——本地兜底工作区无项目
        // 语境不注册（模型不可见）；ask_user 两形态都在。
        assertThat(AgentscopeHarnessAgentFactory
                .interviewToolkit(new ChatAgentWorkspace.Local(null), PRD_PORT).getToolNames())
                .containsExactly(AskUserTool.NAME);
        assertThat(AgentscopeHarnessAgentFactory.interviewToolkit(
                new ChatAgentWorkspace.ProjectDev("42", "ws-42-dev"), PRD_PORT).getToolNames())
                .containsExactlyInAnyOrder(AskUserTool.NAME, SavePrdTool.NAME);
    }
}
