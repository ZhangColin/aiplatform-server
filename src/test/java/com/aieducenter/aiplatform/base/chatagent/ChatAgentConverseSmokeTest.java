package com.aieducenter.aiplatform.base.chatagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.port.ChatAgentClient;
import com.aieducenter.aiplatform.base.chatagent.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope.ChatAgentProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 真实对话冒烟（#44 验收）：平台进程内 HarnessAgent 走 DeepSeek 真模型跑通一轮
 * 对话——流式文本增量可观测（回调序列拼接 = 汇聚文本），该轮产生恰一条 UsageEvent
 * 落库（subject/dims 归属、engine=agentscope）。
 *
 * <p>DEEPSEEK_API_KEY 未设置时整类跳过（Assumption，不失败）：流式/计量/幂等键
 * 行为在 {@code AgentscopeChatAgentClientTest}（mock 事件流）已覆盖，本类只验
 * 真内核接线。workspace 与 AgentState 经临时目录隔离，不写 ~/.agentscope。</p>
 */
@SpringBootTest
class ChatAgentConverseSmokeTest {

    @TempDir
    static Path agentscopeHome;

    @Autowired
    private ChatAgentClient chatAgentClient;

    @Autowired
    private ChatAgentProperties chatAgentProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Path workspaceBackup;

    @BeforeAll
    static void requireDeepseekKey() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "DEEPSEEK_API_KEY 未设置，跳过真实对话冒烟");
        System.setProperty("agentscope.state.home",
                agentscopeHome.resolve("state").toString());
    }

    @BeforeEach
    void isolateWorkspace() {
        workspaceBackup = chatAgentProperties.getWorkspace();
        chatAgentProperties.setWorkspace(agentscopeHome.resolve("workspace"));
    }

    @AfterEach
    void restoreAndClean() {
        chatAgentProperties.setWorkspace(workspaceBackup);
        jdbcTemplate.update("DELETE FROM met_usage_events WHERE run_id LIKE 'smoke-chat-%'");
    }

    @Test
    void given_real_deepseek_when_converse_then_streamed_text_and_usage_recorded() {
        String runId = "smoke-chat-" + UUID.randomUUID();
        List<String> deltas = new ArrayList<>();

        ChatAgentReply reply = chatAgentClient.converse(
                new ChatAgentCommand(runId, "用一句话介绍你自己", null, null,
                        "smoke-session", "smoke-user",
                        new UsageContext("smoke-prj", Map.of("scene", "chatagent-smoke"))),
                deltas::add);

        assertThat(reply.runId()).isEqualTo(runId);
        assertThat(reply.text()).isNotBlank();
        assertThat(String.join("", deltas)).isEqualTo(reply.text());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT subject, provider, engine, input, output "
                        + "FROM met_usage_events WHERE event_id = ?", "chat-usage-" + runId);
        assertThat(row.get("subject")).isEqualTo("smoke-prj");
        assertThat(row.get("provider")).isEqualTo("deepseek");
        assertThat(row.get("engine")).isEqualTo("agentscope");
        assertThat((Long) row.get("input")).isPositive();
        assertThat((Long) row.get("output")).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE run_id = ?", Integer.class, runId))
                .isEqualTo(1);
    }
}
