package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.chatagent.domain.error.ChatAgentMessage;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentWorkspace;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ModelRef;
import com.aieducenter.aiplatform.base.chatagent.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.chatagent.domain.port.ChatAgentClient;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentSessionRecorder;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentWorkspaceClient;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link ChatAgentClient} 的 AgentScope 实现（#44 骨架，#45 平台接线，#48 挂起与
 * 续跑）：平台进程内 HarnessAgent 一轮对话——AgentScope 事件经
 * {@link AgentscopeEventMapper}（映射表单点）转平台 agent 流帧逐个回调 sink
 * （task-start/session-created 开场、text/reasoning/tool/step-* 过程、
 * task-finish/error 收口，runId 锚定；#52 补口：runTurn 前的前段失败——模型解析/
 * agent 工厂构建/工作区解析/会话首查——同样经 error 帧表达，异步轨道起跑失败不再
 * 零帧死寂）；模型调用事件（ReAct 每迭代一条
 * ModelCallEnd）五桶累积，对话结束（含失败轮，已耗 token 如实计量）按命令的
 * usageContext 上报恰一条 UsageEvent（幂等键 chat-usage-{runId}[-{replyId}]，
 * engine=agentscope；归属为空不发明、零用量不报）。
 *
 * <p><b>挂起语义（#48 等待点双向桥）</b>：AgentScope 的权限确认挂起
 * （RequireUserConfirmEvent）= 本轮流软终点——发 {@code wait-raised} 帧（流桥落库
 * 成平台等待点，载荷带恢复私货）后流终止，<b>不发 task-finish</b>（run 未终态，
 * 终态联动不触发）；{@link #resume} 以 ConfirmResult 续跑（同一 (userId,
 * sessionId) 从 AgentStateStore 恢复上下文——平台重启后可续，访谈上下文不丢），
 * 续跑流可再挂起（一 run 多 approve 点）或正常收口。</p>
 *
 * <p>工作区解析（#45）：命令带 workspaceId → 项目 dev 工作区（容器文件面，docker
 * exec 读写，写入即落项目工作区）；缺省 → 适配器配置的本地工作区（#44 口径）。
 * session-created 发射口径（#48 持久化）：带 workspaceId 的会话按 agt_agent_sessions
 * 行首见判定（跨重启不重发，settle 可续跑的前置）；本地兜底维持进程内首见。
 * </p>
 */
@Component
@Adapter(PortType.CLIENT)
public class AgentscopeChatAgentClient implements ChatAgentClient {

    private static final String USAGE_EVENT_PREFIX = "chat-usage-";
    private static final String ENGINE = ChatAgentSessionRecorder.ENGINE;

    /** settle 续跑请求（#48）：等待点 body 的恢复私货 + 用户决策，infrastructure 内部形状。 */
    public record ChatAgentResume(
            String runId,
            String sessionId,
            String userId,
            String workspaceId,
            String modelString,
            String systemPrompt,
            String replyId,
            List<ConfirmResult> confirmResults,
            String resumeText,
            UsageContext usageContext,
            Map<String, Object> streamCorrelation) {
    }

    private final AgentscopeHarnessAgentFactory factory;
    private final ChatAgentProperties properties;
    private final ChatAgentWorkspaceClient workspaceClient;
    private final ChatAgentSessionRecorder sessionRecorder;
    private final UsageEventSink usageEventSink;
    private final ChatAgentResumeGate resumeGate;
    private final Clock clock;
    /** 本地兜底工作区的 sessionId 进程内首见集合（session-created 发射口径）。 */
    private final Set<String> seenLocalSessions = ConcurrentHashMap.newKeySet();

    @Autowired
    public AgentscopeChatAgentClient(AgentscopeHarnessAgentFactory factory,
            ChatAgentProperties properties, ChatAgentWorkspaceClient workspaceClient,
            ChatAgentSessionRecorder sessionRecorder, UsageEventSink usageEventSink,
            ChatAgentResumeGate resumeGate) {
        this(factory, properties, workspaceClient, sessionRecorder, usageEventSink,
                resumeGate, Clock.systemUTC());
    }

    AgentscopeChatAgentClient(AgentscopeHarnessAgentFactory factory,
            ChatAgentProperties properties, ChatAgentWorkspaceClient workspaceClient,
            ChatAgentSessionRecorder sessionRecorder, UsageEventSink usageEventSink,
            ChatAgentResumeGate resumeGate, Clock clock) {
        this.factory = factory;
        this.properties = properties;
        this.workspaceClient = workspaceClient;
        this.sessionRecorder = sessionRecorder;
        this.usageEventSink = usageEventSink;
        this.resumeGate = resumeGate;
        this.clock = clock;
    }

    @Override
    public ChatAgentReply converse(ChatAgentCommand command, Consumer<AgentEvent> sink) {
        PreparedTurn prepared;
        try {
            prepared = prepareTurn(command, sink);
        }
        catch (RuntimeException e) {
            // #52 触达补口：前段（模型解析/agent 工厂构建/工作区解析/会话首查）失败原是
            // 零帧区（续跑闸后台线程吞异常只记日志，用户只见死寂）——补发 error 帧
            // （runId 锚定 = command 的）后照常上抛；converseSilently 的空 sink 无害丢弃，
            // 取名路径失败仍静默保占位（红线不动）
            sink.accept(AgentscopeEventMapper.error(command.runId(), e.getMessage()));
            throw e;
        }
        TurnResult result = runTurn(prepared.agent(),
                List.of(new UserMessage(command.prompt())), prepared.ctx(), prepared.mapper(),
                command.runId(), USAGE_EVENT_PREFIX + command.runId(), command.usageContext(),
                prepared.modelRef(), sink, prepared.resumeContext());
        if (result.error() != null) {
            throw new DomainException(ChatAgentMessage.CONVERSE_FAILED, result.error(),
                    "runId=" + command.runId() + ", model=" + prepared.modelRef().toModelString());
        }
        return new ChatAgentReply(command.runId(), result.text());
    }

    /**
     * 挂起续跑（#48 settle 派发侧调用）：以 ConfirmResult（用户答复/批准/拒绝）经
     * 同一 (userId, sessionId) 恢复上下文续跑——不重发 task-start/session-created
     * （run 已开场），可再挂起（wait-raised 再发）或正常收口（task-finish → 流桥
     * 终态联动）。计量幂等键带 replyId 后缀（挂起轮已报过 chat-usage-{runId}）。
     */
    public void resume(ChatAgentResume resume, Consumer<AgentEvent> sink) {
        ModelRef modelRef;
        ChatAgentWorkspace workspace;
        HarnessAgent agent;
        try {
            modelRef = ModelRef.parse(resume.modelString() != null
                    ? resume.modelString() : properties.getDefaultModel());
            workspace = resolveWorkspace(resume.workspaceId());
            String sysPrompt = resume.systemPrompt() != null
                    ? resume.systemPrompt() : properties.getDefaultSystemPrompt();
            agent = factory.obtain(properties.getAgentName(),
                    sysPrompt, modelRef.toModelString(), workspace);
        }
        catch (RuntimeException e) {
            // #52 同口径补口（resume 原是零帧区）：前段失败发生在续跑闸后台线程
            // （异常被吞只记日志），必须先发 error 帧（runId 锚定）再上抛——
            // 缺 API key 致模型创建失败这类故障，用户侧有明确报错而非死寂
            sink.accept(AgentscopeEventMapper.error(resume.runId(), e.getMessage()));
            throw e;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, resume.confirmResults());
        Msg resumeMsg = UserMessage.builder()
                .textContent(resume.resumeText() != null ? resume.resumeText() : "")
                .metadata(metadata)
                .build();

        AgentscopeEventMapper mapper = new AgentscopeEventMapper(
                resume.runId(), resume.sessionId(), ENGINE);
        runTurn(agent, List.of(resumeMsg), runtimeContext(resume.sessionId(), resume.userId()),
                mapper, resume.runId(),
                USAGE_EVENT_PREFIX + resume.runId() + "-" + resume.replyId(),
                resume.usageContext(), modelRef, sink,
                resumeContextOf(resume.systemPrompt() != null ? resume.systemPrompt()
                        : properties.getDefaultSystemPrompt(),
                        modelRef.toModelString(), resume.userId(),
                        resume.usageContext(), resume.streamCorrelation()));
    }

    // ---------- 内部 ----------

    /** 一轮流的结果（挂起轮 text 为已生成部分；error 非空 = 失败）。 */
    private record TurnResult(String text, Throwable error) {
    }

    /** 前段产物（converse 开场准备至 runTurn 前：模型/agent/上下文/映射表/恢复私货）。 */
    private record PreparedTurn(ModelRef modelRef, HarnessAgent agent, RuntimeContext ctx,
            AgentscopeEventMapper mapper, Map<String, Object> resumeContext) {
    }

    /**
     * 前段准备（#52 从 converse 抽出）：模型解析 → 工作区解析 → agent 工厂构建 →
     * 续跑闸复活 → 开场帧（task-start / session-created 首见）。本段自身不做失败
     * 处理——整段任一失败由 {@link #converse} 补发 error 帧后上抛。
     */
    private PreparedTurn prepareTurn(ChatAgentCommand command, Consumer<AgentEvent> sink) {
        ModelRef modelRef = ModelRef.parse(command.modelString() != null
                ? command.modelString() : properties.getDefaultModel());
        String sysPrompt = command.systemPrompt() != null
                ? command.systemPrompt() : properties.getDefaultSystemPrompt();
        ChatAgentWorkspace workspace = resolveWorkspace(command.workspaceId());

        HarnessAgent agent = factory.obtain(properties.getAgentName(), sysPrompt,
                modelRef.toModelString(), workspace);
        RuntimeContext ctx = runtimeContext(command.sessionId(), command.userId());

        // 新 run 承接会话即复活续跑闸（deny cap 终止只作用于 run，不污染会话）
        resumeGate.reopen(command.sessionId());
        AgentscopeEventMapper mapper = new AgentscopeEventMapper(
                command.runId(), command.sessionId(), ENGINE);
        sink.accept(AgentscopeEventMapper.taskStart(command.runId(), command.prompt(),
                modelRef.toModelString(), ENGINE));
        if (firstSeen(command)) {
            sink.accept(AgentscopeEventMapper.sessionCreated(
                    command.runId(), command.sessionId(), ENGINE));
        }
        return new PreparedTurn(modelRef, agent, ctx, mapper,
                resumeContextOf(sysPrompt, modelRef.toModelString(), command.userId(),
                        command.usageContext(), command.streamCorrelation()));
    }

    /**
     * 一轮流的公共体（converse 首轮与 resume 续跑共用）：事件逐帧映射发射，挂起
     * （RequireUserConfirm）发 wait-raised 后流终止且不发 task-finish；正常收口发
     * task-finish；异常发 error 帧。用量无论成败如实上报（幂等键由调用方给）。
     */
    private TurnResult runTurn(HarnessAgent agent, List<Msg> messages, RuntimeContext ctx,
            AgentscopeEventMapper mapper, String runId, String usageIdempotencyKey,
            UsageContext usageContext, ModelRef modelRef, Consumer<AgentEvent> sink,
            Map<String, Object> resumeContext) {
        StringBuilder text = new StringBuilder();
        AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.ZERO);
        AtomicReference<String> finish = new AtomicReference<>();
        AtomicReference<RequireUserConfirmEvent> suspension = new AtomicReference<>();
        try {
            agent.streamEvents(messages, ctx)
                    .doOnNext(event -> handleEvent(event, mapper, sink, text, usage, finish,
                            suspension, resumeContext))
                    .blockLast(properties.getTimeout());
            if (suspension.get() == null) {
                sink.accept(AgentscopeEventMapper.taskFinish(
                        runId, ctx.getSessionId(), finish.get(), ENGINE));
            }
            return new TurnResult(text.toString(), null);
        }
        catch (Exception e) {
            sink.accept(AgentscopeEventMapper.error(runId, e.getMessage()));
            return new TurnResult(text.toString(), e);
        }
        finally {
            reportUsage(usageIdempotencyKey, usage.get(), runId, ctx.getSessionId(),
                    modelRef, usageContext);
        }
    }

    /** 工作区解析：带 workspaceId → 项目 dev 工作区；缺省 → 本地工作区（#44 口径）。 */
    private ChatAgentWorkspace resolveWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return new ChatAgentWorkspace.Local(properties.getWorkspace());
        }
        WorkspaceHandle handle = workspaceClient.handleOf(workspaceId);
        return new ChatAgentWorkspace.ProjectDev(workspaceId, handle.containerName());
    }

    private RuntimeContext runtimeContext(String sessionId, String userId) {
        return RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .build();
    }

    /**
     * session-created 首见判定（#48 持久化口径）：带 workspaceId 的会话按
     * agt_agent_sessions 行判定（登记 + settle 可续跑前置）；本地兜底进程内集合。
     */
    private boolean firstSeen(ChatAgentCommand command) {
        if (command.workspaceId() == null || command.workspaceId().isBlank()) {
            return seenLocalSessions.add(command.sessionId());
        }
        return sessionRecorder.recordIfAbsent(
                command.workspaceId(), command.sessionId(), command.runId());
    }

    private void handleEvent(io.agentscope.core.event.AgentEvent event,
            AgentscopeEventMapper mapper, Consumer<AgentEvent> sink, StringBuilder text,
            AtomicReference<TokenUsage> usage, AtomicReference<String> finish,
            AtomicReference<RequireUserConfirmEvent> suspension,
            Map<String, Object> resumeContext) {
        if (event instanceof TextBlockDeltaEvent delta) {
            text.append(delta.getDelta());
        }
        else if (event instanceof ModelCallEndEvent end) {
            usage.updateAndGet(total -> total.plus(AgentscopeUsageMapper.toTokenUsage(end.getUsage())));
        }
        else if (event instanceof RequireUserConfirmEvent confirm) {
            // 挂起：记软终点标志后发 wait-raised 帧（流桥落库成等待点），不产透传帧
            suspension.set(confirm);
            mapper.finishToken(event).ifPresent(finish::set);
            sink.accept(mapper.waitRaised(confirm, resumeContext));
            return;
        }
        mapper.finishToken(event).ifPresent(finish::set);
        AgentEvent frame = mapper.map(event);
        if (frame != null) {
            sink.accept(frame);
        }
    }

    /**
     * 挂起帧的恢复私货：settle 侧据此重建 ConfirmResult 与续跑 run 的全部入参
     * （模型档位/会话寻址/计量归属/流关联——等待点 body 跨重启携带）。
     */
    private static Map<String, Object> resumeContextOf(String sysPrompt, String modelString,
            String userId, UsageContext usageContext, Map<String, Object> streamCorrelation) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("modelString", modelString != null ? modelString : "");
        context.put("systemPrompt", sysPrompt != null ? sysPrompt : "");
        context.put("userId", userId != null ? userId : "");
        context.put("usageContext", usageContext != null
                ? Map.of("subject", usageContext.subject(), "dims", usageContext.dims())
                : Map.of());
        context.put("streamCorrelation", streamCorrelation != null ? streamCorrelation : Map.of());
        return context;
    }

    private void reportUsage(String idempotencyKey, TokenUsage total, String runId,
            String sessionId, ModelRef modelRef, UsageContext usageContext) {
        if (usageContext == null || total.total() <= 0) {
            return;
        }
        usageEventSink.report(new UsageEvent(
                idempotencyKey,
                clock.instant(),
                usageContext.subject(),
                runId,
                sessionId,
                modelRef.provider(),
                modelRef.modelId(),
                ENGINE,
                usageContext.dims(),
                total));
    }
}
