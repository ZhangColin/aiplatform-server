package com.aieducenter.aiplatform.base.agentengine.infrastructure.dsh;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.RunResult;
import com.aieducenter.aiplatform.base.agentengine.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.AgentApiKeyResolver;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.AgentModelConfig;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dsh 适配器（headless 边界，票 #20 验收）：一次性任务——无 UsageEvent（无数据不
 * 造数）、无问答通道（恒空 / no-op）、最终文本以引擎透传同构（data 内 part）发出；
 * 模型档位注入 settings.yaml、任务文本 base64 落文件（命令录制断言）。
 */
class DshAdapterTest {

    private RecordingEnvironmentBackend environment;
    private DshAdapter adapter;
    private WorkspaceHandle handle;

    @BeforeEach
    void setUp() {
        environment = new RecordingEnvironmentBackend();
        adapter = new DshAdapter(new AgentModelConfig("deepseek", "deepseek-v4-pro"),
                new FixedKeyResolver(), environment, 30);
        handle = WorkspaceHandle.dev(WorkspaceId.generate(), "ws-dsh", "net-dsh", 0, 0);
    }

    @Test
    void given_headless_run_when_runTask_then_final_text_part_and_finish_without_usage()
            throws InterruptedException {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        environment.resultByFragment.put("cd /workspace", new ExecResult("最终回答文本", "", 0));

        RunResult result = adapter.runTask(handle, command("run-1", null), events::add);

        assertThat(result.accepted()).isTrue();
        assertThat(result.sessionId()).startsWith("dsh-");
        awaitEnd(events);
        assertThat(events).extracting(AgentEvent::type).containsExactly(
                AgentEventTypes.TASK_START, AgentEventTypes.SESSION_CREATED,
                "text", AgentEventTypes.TASK_FINISH);
        // 引擎透传同构：data 内为合成的最终文本 part（headless 唯一可透传物）
        AgentEvent text = events.get(2);
        assertThat(text.payload()).containsEntry("runId", "run-1");
        assertThat(text.payload().get("data"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "text")
                .containsEntry("text", "最终回答文本");
    }

    @Test
    void given_usage_context_when_run_finishes_then_no_usage_event_reported() throws InterruptedException {
        // headless 无 usage 可采：即便调用方归属（usageContext 非空）也不发（A1 §2.3）
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        environment.resultByFragment.put("cd /workspace", new ExecResult("ok", "", 0));

        adapter.runTask(handle, command("run-2",
                new UsageContext("ws-1", Map.of())), events::add);
        awaitEnd(events);

        // 适配器无从上报（未注入 sink）——以事件流完整性 + 无异常通过为准；
        // 「无 UsageEvent」的落库断言见 AgentTaskAppService 集成面（DshAdapter 无 sink 依赖）
        assertThat(events).extracting(AgentEvent::type).contains(AgentEventTypes.TASK_FINISH);
    }

    @Test
    void given_model_id_when_runTask_then_settings_yaml_written_with_tier() throws InterruptedException {
        environment.resultByFragment.put("cd /workspace", new ExecResult("ok", "", 0));
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        adapter.runTask(handle, command("run-3", null), events::add);
        awaitEnd(events);

        // settings.yaml 经 base64 落盘（防 shell 转义），断言解码后的注入内容
        String b64 = environment.commands.stream()
                .filter(c -> c.contains("settings.yaml"))
                .map(c -> c.replaceAll(".*printf '%s' '([A-Za-z0-9+/=]+)'.*", "$1"))
                .findFirst().orElse("");
        String yaml = new String(java.util.Base64.getDecoder().decode(b64),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(yaml).contains("provider: deepseek-official");
        assertThat(yaml).contains("model: deepseek-v4-pro");
        assertThat(yaml).contains("reasoningEffort: max");
    }

    @Test
    void given_dsh_exit_failure_when_runTask_then_error_event() throws InterruptedException {
        environment.resultByFragment.put("cd /workspace", new ExecResult("", "boom", 1));
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        adapter.runTask(handle, command("run-4", null), events::add);
        awaitEnd(events);

        assertThat(events).extracting(AgentEvent::type).contains(AgentEventTypes.ERROR);
    }

    @Test
    void given_dsh_engine_when_interactions_then_noop_and_empty() {
        assertThat(adapter.pendingQuestions(handle, "dsh-1")).isEmpty();

        adapter.replyQuestions(handle, "dsh-1", "que_1", List.of(List.of("A")));
        adapter.replyPermission(handle, "dsh-1", "perm_1", true);

        assertThat(environment.commands.stream().noneMatch(c -> c.contains("/question")))
                .isTrue();
    }

    @Test
    void given_dsh_cli_present_when_health_then_true() {
        environment.resultByFragment.put("command -v dsh", new ExecResult("dsh 0.1.0", "", 0));

        assertThat(adapter.health(handle)).isTrue();
    }

    @Test
    void given_dsh_cli_missing_when_health_then_false() {
        environment.resultByFragment.put("command -v dsh", new ExecResult("", "not found", 127));

        assertThat(adapter.health(handle)).isFalse();
    }

    // ---------- 测试替身 ----------

    private AgentTaskCommand command(String runId, UsageContext usageContext) {
        return new AgentTaskCommand(runId, "写个落地页", "你是开发工程师", null, null,
                usageContext);
    }

    private void awaitEnd(List<AgentEvent> events) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && events.stream().noneMatch(e -> e.type().equals(AgentEventTypes.TASK_FINISH)
                || e.type().equals(AgentEventTypes.ERROR))) {
            Thread.sleep(50);
        }
        assertThat(events).extracting(AgentEvent::type)
                .containsAnyOf(AgentEventTypes.TASK_FINISH, AgentEventTypes.ERROR);
    }

    /** 环境后端替身：按命令片段回放结果并录制全部命令；缺省成功（文件写入等辅助命令）。 */
    private static final class RecordingEnvironmentBackend implements EnvironmentBackend {

        final List<String> commands = new CopyOnWriteArrayList<>();
        final Map<String, ExecResult> resultByFragment = new java.util.concurrent.ConcurrentHashMap<>();

        private ExecResult respond(String command) {
            return resultByFragment.entrySet().stream()
                    .filter(e -> command.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(new ExecResult("", "", 0));
        }

        @Override
        public com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision createWorkspace(
                WorkspaceId workspaceId,
                com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind kind) {
            throw new UnsupportedOperationException("测试不触达");
        }

        @Override
        public void destroyWorkspace(WorkspaceHandle handle) {
            throw new UnsupportedOperationException("测试不触达");
        }

        @Override
        public ExecResult exec(WorkspaceHandle handle, String command) {
            commands.add(command);
            return respond(command);
        }

        @Override
        public java.net.URI exposePort(WorkspaceHandle handle, int containerPort) {
            throw new UnsupportedOperationException("测试不触达");
        }

        @Override
        public byte[] packSource(WorkspaceHandle handle) {
            throw new UnsupportedOperationException("测试不触达");
        }
    }

    /** Key 解析替身：恒定值（不读环境，测试可复现）。 */
    private static final class FixedKeyResolver extends AgentApiKeyResolver {

        FixedKeyResolver() {
            super(new AgentModelConfig("deepseek", "deepseek-v4-pro"));
        }

        @Override
        public String resolve() {
            return "test-key";
        }
    }
}
