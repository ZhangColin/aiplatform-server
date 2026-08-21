package com.aieducenter.aiplatform.base.agentengine.infrastructure.dsh;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import lombok.extern.slf4j.Slf4j;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.RunResult;
import com.aieducenter.aiplatform.base.agentengine.domain.port.CodingAgentAdapter;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.AgentApiKeyResolver;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.AgentModelConfig;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;

/**
 * DshAdapter —— 开发智能体适配层的第二个实现（ADR-0004 headless 边界，demo 同构
 * 重写）：平台后端 --环境抽象 exec--> 容器内 {@code dsh --profile headless "<任务>"}
 * （一次性任务：创建 Agent、提交任务、等停稳、打印最后一条非空 assistant 文本、
 * 退出；不监听端口，不需要 serve 进程）。
 *
 * <p>能力如实暴露（A1 §1.5 矩阵）：无问答通道（pendingQuestions 恒空、reply
 * no-op）、无权限审批（replyPermission no-op）、无过程流（只发最终文本，实时事件
 * 需 DSH 服务化，既定缝不进 Phase A）。会话非引擎概念——sessionId 由本适配器
 * 生成（{@code dsh-*}），仅作任务关联。</p>
 *
 * <p>用量埋点：headless 无 usage 可采——不发 UsageEvent（无数据不造数）；
 * dims 预留 {@code source=estimated} 标注，DSH 服务化后补（A1 §2.3）。</p>
 */
@Component
@Adapter(PortType.CLIENT)
@Slf4j
public class DshAdapter implements CodingAgentAdapter {

    public static final String ENGINE = "dsh";

    /** DSH 官方 DeepSeek API 适配器 id（settings.yaml 的 agent-default-model.provider）。 */
    private static final String DSH_PROVIDER = "deepseek-official";

    /** headless 输出唯一可透传的引擎 part 形态：最终 assistant 文本。 */
    private static final String FINAL_TEXT_PART = "text";

    private final AgentModelConfig modelConfig;
    private final AgentApiKeyResolver apiKeyResolver;
    private final EnvironmentBackend environmentBackend;
    private final Duration taskTimeout;
    private final ExecutorService executor;
    private final AtomicInteger threadSeq = new AtomicInteger();

    public DshAdapter(AgentModelConfig modelConfig,
                      AgentApiKeyResolver apiKeyResolver,
                      EnvironmentBackend environmentBackend,
                      @Value("${app.agent.timeout-minutes:30}") long timeoutMinutes) {
        this.modelConfig = modelConfig;
        this.apiKeyResolver = apiKeyResolver;
        this.environmentBackend = environmentBackend;
        this.taskTimeout = Duration.ofMinutes(timeoutMinutes);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "dsh-task-" + threadSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public String engine() {
        return ENGINE;
    }

    @Override
    public String label() {
        return "DeepSeek Harness (DSH)";
    }

    @Override
    public String note() {
        return "headless 一次性任务：无交互提问，结束后输出最终文本";
    }

    @Override
    public boolean supportsQuestions() {
        return false;
    }

    @Override
    public boolean supportsPermissions() {
        return false;
    }

    @Override
    public RunResult runTask(WorkspaceHandle handle, AgentTaskCommand command,
                             Consumer<AgentEvent> sink) {
        String sessionId = "dsh-" + Long.toUnsignedString(System.currentTimeMillis(), 36)
                + "-" + Long.toUnsignedString(System.nanoTime() & 0xffff, 16);
        String model = modelConfig.resolve(command.modelId());
        sink.accept(new AgentEvent(AgentEventTypes.TASK_START, Map.of(
                "runId", command.runId(),
                "prompt", command.prompt(),
                "model", model,
                "engine", ENGINE)));
        sink.accept(new AgentEvent(AgentEventTypes.SESSION_CREATED, Map.of(
                "runId", command.runId(),
                "sessionId", sessionId,
                "engine", ENGINE)));
        executor.submit(() -> runHeadless(handle, command, sessionId, sink));
        return new RunResult(command.runId(), sessionId, true);
    }

    private void runHeadless(WorkspaceHandle handle, AgentTaskCommand command, String sessionId,
                             Consumer<AgentEvent> sink) {
        try {
            // 1) 模型档位注入：settings.yaml 每次任务前重写（DSH 读全局默认模型）
            writeModelSettings(handle, command.modelId());
            // 2) 任务文本落文件（base64 中转，规避 shell 引号/换行转义）；
            //    systemPrompt 是入参（角色卡），拼在任务文本前——DSH 无独立 system 通道
            String taskText = (command.systemPrompt() == null || command.systemPrompt().isBlank()
                    ? "" : command.systemPrompt() + "\n\n")
                    + "【任务】\n" + command.prompt();
            writeTaskFile(handle, taskText);
            // 3) 容器内执行 dsh headless（cwd=/workspace；key 经进程环境注入，不进镜像不落盘）
            Future<ExecResult> future = executor.submit(() -> environmentBackend.exec(handle,
                    "cd /workspace && " + apiKeyResolver.envPrefix()
                            + "dsh --profile headless \"$(cat /tmp/dsh-task.txt)\""));
            ExecResult result = future.get(taskTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result.exitCode() != 0) {
                String err = result.stderr().isBlank() ? "未知错误" : result.stderr().strip();
                sink.accept(new AgentEvent(AgentEventTypes.ERROR, Map.of(
                        "runId", command.runId(),
                        "message", "dsh 任务失败（exit " + result.exitCode() + "）: "
                                + tail(err, 500))));
                return;
            }
            String text = result.stdout().strip();
            if (!text.isEmpty()) {
                // 引擎透传同构：data 内为合成的最终文本 part（headless 唯一可透传物）
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("type", FINAL_TEXT_PART);
                data.put("text", text);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("runId", command.runId());
                payload.put("sessionId", sessionId);
                payload.put("engine", ENGINE);
                payload.put("data", data);
                sink.accept(new AgentEvent(FINAL_TEXT_PART, payload));
            }
            sink.accept(new AgentEvent(AgentEventTypes.TASK_FINISH, Map.of(
                    "runId", command.runId(),
                    "sessionId", sessionId,
                    "engine", ENGINE,
                    "finish", "end")));
        } catch (TimeoutException e) {
            // [d]sh 括号技巧：避免 pkill 匹配到承载它的 sh -c 包装进程（只杀真正的 dsh 进程）
            environmentBackend.exec(handle, "pkill -f '[d]sh --profile headless' || true");
            sink.accept(new AgentEvent(AgentEventTypes.ERROR, Map.of(
                    "runId", command.runId(),
                    "message", "任务超时（超过 " + taskTimeout.toMinutes() + " 分钟），已终止 dsh 进程")));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("[agentengine] dsh run {} 执行失败: {}", command.runId(), msg);
            sink.accept(new AgentEvent(AgentEventTypes.ERROR, Map.of(
                    "runId", command.runId(),
                    "message", msg)));
        }
    }

    @Override
    public List<Map<String, Object>> pendingQuestions(WorkspaceHandle handle, String sessionId) {
        // DSH headless 为一次性任务：无交互提问 surface（提问通道只有 opencode serve 的 que_ 机制）
        return List.of();
    }

    @Override
    public void replyQuestions(WorkspaceHandle handle, String sessionId, String requestId,
                               List<List<String>> answers) {
        // no-op：headless 模式没有提问等待状态（见 pendingQuestions 说明）
    }

    @Override
    public void replyPermission(WorkspaceHandle handle, String sessionId, String permissionId,
                                boolean approve) {
        // no-op：headless 模式没有权限审批通道
    }

    @Override
    public boolean abort(WorkspaceHandle handle, String sessionId) {
        // 无可终止的常驻运行：headless 一次性任务随 exec 结束；deny cap 依赖权限通道
        // （本引擎恒无权限等待点），此路径实际不可达——如实返回 false
        return false;
    }

    @Override
    public boolean health(WorkspaceHandle handle) {
        // 环境里装了 dsh 且 CLI 可用 = 就绪（不需要 serve 进程）
        ExecResult r = environmentBackend.exec(handle, "command -v dsh >/dev/null 2>&1 && dsh --version");
        return r.exitCode() == 0;
    }

    // ---------- 内部 ----------

    /** 把模型档位写进 $DSH_HOME/settings.yaml（agent-default-model 段，DSH 全局默认模型）。 */
    private void writeModelSettings(WorkspaceHandle handle, String modelId) {
        String model = modelConfig.resolve(modelId);
        String yaml = "agent-default-model:\n"
                + "  provider: " + DSH_PROVIDER + "\n"
                + "  model: " + model + "\n"
                // 与 opencode 配置对齐：平台主模型（deepseek-v4-pro）用 max 推理；flash 档不设
                + (model.equals(modelConfig.model()) ? "  reasoningEffort: max\n" : "");
        ExecResult r = execBase64(handle, yaml, "/root/.dsh/settings.yaml", true);
        if (!r.ok()) {
            throw new IllegalStateException("写入 DSH 模型配置失败: " + r.stderr());
        }
    }

    private void writeTaskFile(WorkspaceHandle handle, String taskText) {
        ExecResult r = execBase64(handle, taskText, "/tmp/dsh-task.txt", false);
        if (!r.ok()) {
            throw new IllegalStateException("写入 dsh 任务文件失败: " + r.stderr());
        }
    }

    private ExecResult execBase64(WorkspaceHandle handle, String content, String target,
                                  boolean mkdir) {
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String prefix = mkdir ? "mkdir -p $(dirname " + target + ") && " : "";
        return environmentBackend.exec(handle,
                prefix + "printf '%s' '" + b64 + "' | base64 -d > " + target);
    }

    private String tail(String s, int max) {
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
