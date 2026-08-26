package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentWorkspace;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentscopeHarnessAgentFactory} 实例缓存与生命周期（#44：HarnessAgent
 * 无状态可复用，per-session 靠 RuntimeContext，同规格构建恰一次；#45：工作区
 * 身份入规格键——不同容器不复用、同容器不同形态不复用）。
 */
class AgentscopeHarnessAgentFactoryTest {

    private AgentscopeHarnessAgentFactory factoryWith(List<HarnessAgent> created) {
        return new AgentscopeHarnessAgentFactory(
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
}
