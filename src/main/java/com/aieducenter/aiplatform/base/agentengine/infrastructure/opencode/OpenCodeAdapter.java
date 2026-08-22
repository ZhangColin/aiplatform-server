package com.aieducenter.aiplatform.base.agentengine.infrastructure.opencode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.RunResult;
import com.aieducenter.aiplatform.base.agentengine.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.agentengine.domain.port.CodingAgentAdapter;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.AgentModelConfig;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;

/**
 * OpenCodeAdapter —— 开发智能体适配层的首个实现（片2a，demo 同构重写）。
 *
 * <p>接线：平台后端 --HTTP--> dev 容器内 {@code opencode serve}（宿主 hostPort 映射
 * 容器 4096，Basic Auth；serve 拉起归 {@link OpenCodeServeBootstrap}）。</p>
 *
 * <p><b>已知限制（PoC 项，demo 同构）</b>：message 端点同步返回，parts 在 run 结束时
 * 整批透传——「增量过程流」的逐 part 实时性需订阅 opencode 事件总线（/event SSE），
 * 留作升级路径（B0 §5.3：未实测假设进 PoC 清单）。已核对的一手 API（opencode 1.18，
 * /doc OpenAPI）：</p>
 * <pre>
 *   POST /session?directory=/workspace                  建会话
 *   POST /session/{id}/message {parts, model, system}   发消息（同步；agent 提问时阻塞等待）
 *   GET  /question                                       待回答的问题（全局 que_ 机制，按 sessionID 过滤）
 *   POST /question/{requestID}/reply                    回答 {answers:[[label,..],..]}
 *   GET  /permission                                     挂起中的权限列表（按 sessionID 过滤）
 *   POST /session/{id}/permissions/{permissionID}       审批 {response: once|reject}
 *   GET  /event                                          事件总线（SSE；权限发现快路，片2b）
 *   POST /session/{id}/abort                             终止会话当前运行（deny cap 平台终止）
 *   GET  /global/health                                  健康检查
 * </pre>
 *
 * <p>等待点发现（片2b）：run 存续期由 {@link OpenCodeWaitWatcher} 盯住两类挂起——
 * 权限走 permission 轮询兜底 + 事件总线快路、问答走 question 轮询——检出即
 * {@code wait-raised} 上报 sink，落库归 agentengine 应用层流桥。</p>
 *
 * <p>用量埋点（A1 §2.3 落位）：step-finish 增量在 {@link RunUsageAccumulator} 求和，
 * run 结束（含失败路径）上报 {@code UsageEventSink} 恰一条——幂等键
 * {@code agt-usage-{runId}}，first-write-wins；usageContext 为空（调用方不归属）
 * 则不报。</p>
 */
@Component
@Adapter(PortType.CLIENT)
@Slf4j
public class OpenCodeAdapter implements CodingAgentAdapter {

    public static final String ENGINE = "opencode";

    private static final String STEP_FINISH = "step-finish";
    /** UsageEvent 幂等键前缀（run 级一条，runId 唯一）。 */
    private static final String USAGE_EVENT_PREFIX = "agt-usage-";

    private final OpenCodeServeBootstrap bootstrap;
    private final AgentModelConfig modelConfig;
    private final UsageEventSink usageEventSink;
    private final Duration taskTimeout;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)   // opencode serve 是 node:http；HTTP/1.1 最稳
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor;
    private final AtomicInteger threadSeq = new AtomicInteger();

    @Autowired
    public OpenCodeAdapter(OpenCodeServeBootstrap bootstrap,
                           AgentModelConfig modelConfig,
                           UsageEventSink usageEventSink,
                           @Value("${app.agent.timeout-minutes:30}") long timeoutMinutes) {
        this(bootstrap, modelConfig, usageEventSink, timeoutMinutes, Clock.systemUTC());
    }

    public OpenCodeAdapter(OpenCodeServeBootstrap bootstrap,
                           AgentModelConfig modelConfig,
                           UsageEventSink usageEventSink,
                           long timeoutMinutes,
                           Clock clock) {
        this.bootstrap = bootstrap;
        this.modelConfig = modelConfig;
        this.usageEventSink = usageEventSink;
        this.taskTimeout = Duration.ofMinutes(timeoutMinutes);
        this.clock = clock;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "opencode-task-" + threadSeq.incrementAndGet());
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
        return "OpenCode";
    }

    @Override
    public String note() {
        return "无头 HTTP serve：支持交互提问与权限审批";
    }

    @Override
    public boolean supportsQuestions() {
        return true;
    }

    @Override
    public boolean supportsPermissions() {
        return true;
    }

    @Override
    public RunResult runTask(WorkspaceHandle handle, AgentTaskCommand command,
                             Consumer<AgentEvent> sink) {
        String model = resolveModel(command.modelId());
        sink.accept(new AgentEvent(AgentEventTypes.TASK_START, Map.of(
                "runId", command.runId(),
                "prompt", command.prompt(),
                "model", model,
                "engine", ENGINE)));
        OpenCodeServeBootstrap.ServeEndpoint endpoint;
        String sessionId = command.sessionId();
        try {
            endpoint = bootstrap.ensureRunning(handle);
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = createSession(endpoint, command.prompt());
                sink.accept(new AgentEvent(AgentEventTypes.SESSION_CREATED, Map.of(
                        "runId", command.runId(),
                        "sessionId", sessionId,
                        "engine", ENGINE)));
            }
        } catch (Exception e) {
            // 拉起/建会话失败：同步拒绝，失败原因经 error 事件表达（不抛，异步契约）
            sink.accept(new AgentEvent(AgentEventTypes.ERROR, Map.of(
                    "runId", command.runId(),
                    "message", messageOf(e))));
            return RunResult.rejected(command.runId());
        }
        // 异步执行：agent 提问时会长时间阻塞消息调用，不能占着请求线程
        String sid = sessionId;
        executor.submit(() -> sendMessageAndEmit(endpoint, command, sid, model, sink));
        return new RunResult(command.runId(), sessionId, true);
    }

    private void sendMessageAndEmit(OpenCodeServeBootstrap.ServeEndpoint endpoint,
                                    AgentTaskCommand command, String sessionId, String model,
                                    Consumer<AgentEvent> sink) {
        RunUsageAccumulator usage = new RunUsageAccumulator();
        // 等待点发现通道（片2b）：run 存续期盯住引擎的问答/权限挂起——消息发送
        // 期间 agent 提问或请求权限即经 wait-raised 上报；run 结束即停（finally）
        OpenCodeWaitWatcher watcher = OpenCodeWaitWatcher.start(
                endpoint, http, mapper, command.runId(), sessionId, sink);
        try {
            // 角色卡（systemPrompt 入参，B0：适配层零角色概念）经 system 字段注入；
            // 模型按调用方档位显式指定（message schema: {providerID, modelID}）
            ObjectNode body = mapper.createObjectNode()
                    .set("parts", mapper.createArrayNode()
                            .add(mapper.createObjectNode().put("type", "text")
                                    .put("text", command.prompt())));
            body.set("model", mapper.createObjectNode()
                    .put("providerID", modelConfig.provider())
                    .put("modelID", model));
            if (command.systemPrompt() != null && !command.systemPrompt().isBlank()) {
                body.put("system", command.systemPrompt());
            }
            JsonNode resp = send(endpoint, "POST",
                    endpoint.baseUrl() + "/session/" + sessionId + "/message", body);
            if (resp == null) {
                sink.accept(new AgentEvent(AgentEventTypes.ERROR, Map.of(
                        "runId", command.runId(),
                        "message", "任务未返回结果")));
                return;
            }
            emitParts(resp, command, sessionId, usage, sink);
            sink.accept(new AgentEvent(AgentEventTypes.TASK_FINISH, Map.of(
                    "runId", command.runId(),
                    "sessionId", sessionId,
                    "engine", ENGINE,
                    "finish", resp.path("info").path("finish").asText(""))));
        } catch (Exception e) {
            String msg = messageOf(e);
            if (e instanceof java.net.http.HttpTimeoutException) {
                msg = "任务超时（超过 " + taskTimeout.toMinutes() + " 分钟）";
            }
            log.warn("[agentengine] opencode run {} 执行失败: {}", command.runId(), msg);
            sink.accept(new AgentEvent(AgentEventTypes.ERROR, Map.of(
                    "runId", command.runId(),
                    "message", msg)));
        } finally {
            watcher.stop();
            reportUsage(command, sessionId, usage.total());
        }
    }

    /**
     * parts 逐个透传（引擎 part 原样进 {@code data} 键）；step-finish 的 token
     * 增量就地累加。包级可见：累加与透传语义的单测缝。
     */
    void emitParts(JsonNode resp, AgentTaskCommand command, String sessionId,
                   RunUsageAccumulator usage, Consumer<AgentEvent> sink) {
        for (JsonNode part : resp.path("parts")) {
            String partType = part.path("type").asText();
            if (STEP_FINISH.equals(partType)) {
                usage.addStepFinish(part);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", command.runId());
            payload.put("sessionId", sessionId);
            payload.put("engine", ENGINE);
            payload.put("data", mapper.convertValue(part, Map.class));
            sink.accept(new AgentEvent(partType, payload));
        }
    }

    /**
     * run 级用量上报（恰一条，幂等键 agt-usage-{runId}）：调用方未归属
     * （usageContext 空）则不报——归属是业务编排层的决定，底座不代填。
     */
    private void reportUsage(AgentTaskCommand command, String sessionId, TokenUsage total) {
        UsageContext context = command.usageContext();
        if (context == null) {
            return;
        }
        usageEventSink.report(new UsageEvent(
                USAGE_EVENT_PREFIX + command.runId(),
                clock.instant(),
                context.subject(),
                command.runId(),
                sessionId,
                modelConfig.provider(),
                resolveModel(command.modelId()),
                ENGINE,
                context.dims(),
                total));
    }

    @Override
    public List<Map<String, Object>> pendingQuestions(WorkspaceHandle handle, String sessionId) {
        try {
            OpenCodeServeBootstrap.ServeEndpoint endpoint = bootstrap.ensureRunning(handle);
            // 提问挂在全局 GET /question（que_ 机制）；会话级接口不返回 question 工具的问题
            JsonNode resp = send(endpoint, "GET", endpoint.baseUrl() + "/question", null);
            if (resp == null || !resp.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode req : resp) {
                if (sessionId != null && !sessionId.isBlank()
                        && !sessionId.equals(req.path("sessionID").asText())) {
                    continue;
                }
                out.add(mapper.convertValue(req, Map.class));
            }
            return out;
        } catch (Exception e) {
            log.warn("[agentengine] 拉取待答问题失败（workspace {}）：{}",
                    handle.workspaceId().value(), messageOf(e));
            return List.of();
        }
    }

    @Override
    public void replyQuestions(WorkspaceHandle handle, String sessionId, String requestId,
                               List<List<String>> answers) {
        try {
            OpenCodeServeBootstrap.ServeEndpoint endpoint = bootstrap.ensureRunning(handle);
            var arr = mapper.createArrayNode();
            for (List<String> answer : answers) {
                var inner = mapper.createArrayNode();
                answer.forEach(inner::add);
                arr.add(inner);
            }
            ObjectNode body = mapper.createObjectNode().set("answers", arr);
            send(endpoint, "POST", endpoint.baseUrl() + "/question/" + requestId + "/reply", body);
        } catch (Exception e) {
            throw new IllegalStateException("回答问题失败: " + messageOf(e), e);
        }
    }

    @Override
    public void replyPermission(WorkspaceHandle handle, String sessionId, String permissionId,
                                boolean approve) {
        try {
            OpenCodeServeBootstrap.ServeEndpoint endpoint = bootstrap.ensureRunning(handle);
            send(endpoint, "POST",
                    endpoint.baseUrl() + "/session/" + sessionId + "/permissions/" + permissionId,
                    mapper.createObjectNode().put("response", approve ? "once" : "reject"));
        } catch (Exception e) {
            throw new IllegalStateException("审批回复失败: " + messageOf(e), e);
        }
    }

    @Override
    public boolean abort(WorkspaceHandle handle, String sessionId) {
        try {
            OpenCodeServeBootstrap.ServeEndpoint endpoint = bootstrap.ensureRunning(handle);
            // POST /session/{id}/abort：中止会话当前运行（deny cap 平台终止）；
            // 被阻塞的 message 调用随之中止返回 → 异步路径发 error/task-finish
            send(endpoint, "POST",
                    endpoint.baseUrl() + "/session/" + sessionId + "/abort",
                    mapper.createObjectNode());
            return true;
        } catch (Exception e) {
            log.warn("[agentengine] 终止 opencode 会话运行失败（session {}）：{}",
                    sessionId, messageOf(e));
            return false;
        }
    }

    @Override
    public boolean health(WorkspaceHandle handle) {
        return bootstrap.isRunning(handle);
    }

    // ---------- 内部 ----------

    private String createSession(OpenCodeServeBootstrap.ServeEndpoint endpoint,
                                 String prompt) throws Exception {
        ObjectNode body = mapper.createObjectNode()
                .put("title", prompt.length() > 40 ? prompt.substring(0, 40) : prompt);
        JsonNode resp = send(endpoint, "POST",
                endpoint.baseUrl() + "/session?directory=/workspace", body);
        if (resp == null || !resp.has("id")) {
            throw new IllegalStateException("创建会话失败: " + resp);
        }
        return resp.path("id").asText();
    }

    private JsonNode send(OpenCodeServeBootstrap.ServeEndpoint endpoint, String method,
                          String url, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", endpoint.authHeader())
                .header("Content-Type", "application/json")
                .timeout(taskTimeout);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("opencode HTTP " + resp.statusCode() + ": "
                    + (resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body()));
        }
        if (resp.body().isBlank()) {
            return null;
        }
        JsonNode json = mapper.readTree(resp.body());
        // opencode 错误形态：{"name":"UnknownError","data":{"message":...}}
        if (json.has("name") && json.path("data").has("message")) {
            throw new IllegalStateException("opencode 错误: " + json.path("name").asText()
                    + " — " + json.path("data").path("message").asText());
        }
        return json;
    }

    private String resolveModel(String modelId) {
        return modelConfig.resolve(modelId);
    }

    private String messageOf(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
