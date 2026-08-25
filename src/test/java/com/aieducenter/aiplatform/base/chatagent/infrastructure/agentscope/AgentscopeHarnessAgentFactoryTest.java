package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentscopeHarnessAgentFactory} 实例缓存与生命周期（#44：HarnessAgent
 * 无状态可复用，per-session 靠 RuntimeContext，同规格构建恰一次）。
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

        HarnessAgent first = factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash", null);
        HarnessAgent second = factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash", null);

        assertThat(second).isSameAs(first);
        assertThat(created).hasSize(1);
    }

    @Test
    void given_different_spec_when_obtain_then_new_instance_per_spec() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash", null);
        factory.obtain("chat-agent", "另一个 sys", "deepseek:deepseek-v4-flash", null);
        factory.obtain("chat-agent", "sys", "deepseek:deepseek-chat", null);
        factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash",
                Path.of("/tmp/other-workspace"));

        assertThat(created).hasSize(4);
    }

    @Test
    void given_cached_agents_when_destroy_then_all_closed_and_cache_cleared() throws IOException {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);
        factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash", null);
        factory.obtain("chat-agent", "sys2", "deepseek:deepseek-v4-flash", null);

        factory.destroy();

        for (HarnessAgent agent : created) {
            verify(agent, times(1)).close();
        }
        factory.obtain("chat-agent", "sys", "deepseek:deepseek-v4-flash", null);
        assertThat(created).hasSize(3);
    }
}
