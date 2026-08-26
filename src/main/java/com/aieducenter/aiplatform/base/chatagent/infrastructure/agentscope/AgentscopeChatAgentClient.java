package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.chatagent.domain.error.ChatAgentMessage;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentWorkspace;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ModelRef;
import com.aieducenter.aiplatform.base.chatagent.domain.port.ChatAgentClient;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentWorkspaceClient;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link ChatAgentClient} 的 AgentScope 实现（#44 骨架，#45 平台接线）：平台进程内
 * HarnessAgent 一轮对话——AgentScope 事件经 {@link AgentscopeEventMapper}（映射表
 * 单点）转平台 agent 流帧逐个回调 sink（task-start/session-created 开场、
 * text/reasoning/tool/step-* 过程、task-finish/error 收口，runId 锚定）；模型调用
 * 事件（ReAct 每迭代一条 ModelCallEnd）五桶累积，对话结束（含失败轮，已耗 token
 * 如实计量）按命令的 usageContext 上报恰一条 UsageEvent（幂等键
 * chat-usage-{runId}，engine=agentscope；归属为空不发明、零用量不报）。
 *
 * <p>工作区解析（#45）：命令带 workspaceId → 项目 dev 工作区（容器文件面，docker
 * exec 读写，写入即落项目工作区）；缺省 → 适配器配置的本地工作区（#44 口径）。</p>
 *
 * <p>session-created 发射口径：sessionId 进程内首见才发（复用不发，SSE事件清单）。
 * 进程内集合是过渡实现——跨重启的会话登记与恢复归 #48。</p>
 */
@Component
@Adapter(PortType.CLIENT)
public class AgentscopeChatAgentClient implements ChatAgentClient {

    private static final String USAGE_EVENT_PREFIX = "chat-usage-";
    private static final String ENGINE = "agentscope";

    private final AgentscopeHarnessAgentFactory factory;
    private final ChatAgentProperties properties;
    private final ChatAgentWorkspaceClient workspaceClient;
    private final UsageEventSink usageEventSink;
    private final Clock clock;
    /** sessionId 进程内首见集合（session-created 发射口径；持久化归 #48）。 */
    private final Set<String> seenSessions = ConcurrentHashMap.newKeySet();

    @Autowired
    public AgentscopeChatAgentClient(AgentscopeHarnessAgentFactory factory,
            ChatAgentProperties properties, ChatAgentWorkspaceClient workspaceClient,
            UsageEventSink usageEventSink) {
        this(factory, properties, workspaceClient, usageEventSink, Clock.systemUTC());
    }

    AgentscopeChatAgentClient(AgentscopeHarnessAgentFactory factory,
            ChatAgentProperties properties, ChatAgentWorkspaceClient workspaceClient,
            UsageEventSink usageEventSink, Clock clock) {
        this.factory = factory;
        this.properties = properties;
        this.workspaceClient = workspaceClient;
        this.usageEventSink = usageEventSink;
        this.clock = clock;
    }

    @Override
    public ChatAgentReply converse(ChatAgentCommand command, Consumer<AgentEvent> sink) {
        ModelRef modelRef = ModelRef.parse(command.modelString() != null
                ? command.modelString() : properties.getDefaultModel());
        String sysPrompt = command.systemPrompt() != null
                ? command.systemPrompt() : properties.getDefaultSystemPrompt();
        ChatAgentWorkspace workspace = resolveWorkspace(command.workspaceId());

        HarnessAgent agent = factory.obtain(properties.getAgentName(), sysPrompt,
                modelRef.toModelString(), workspace);
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(command.sessionId())
                .userId(command.userId())
                .build();

        AgentscopeEventMapper mapper = new AgentscopeEventMapper(
                command.runId(), command.sessionId(), ENGINE);
        sink.accept(AgentscopeEventMapper.taskStart(command.runId(), command.prompt(),
                modelRef.toModelString(), ENGINE));
        if (seenSessions.add(command.sessionId())) {
            sink.accept(AgentscopeEventMapper.sessionCreated(
                    command.runId(), command.sessionId(), ENGINE));
        }

        StringBuilder text = new StringBuilder();
        AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.ZERO);
        AtomicReference<String> finish = new AtomicReference<>();
        try {
            agent.streamEvents(new UserMessage(command.prompt()), ctx)
                    .doOnNext(event -> handleEvent(event, mapper, sink, text, usage, finish))
                    .blockLast(properties.getTimeout());
            sink.accept(AgentscopeEventMapper.taskFinish(
                    command.runId(), command.sessionId(), finish.get(), ENGINE));
        }
        catch (Exception e) {
            sink.accept(AgentscopeEventMapper.error(command.runId(), e.getMessage()));
            throw new DomainException(ChatAgentMessage.CONVERSE_FAILED, e,
                    "runId=" + command.runId() + ", model=" + modelRef.toModelString());
        }
        finally {
            // 失败轮已消耗的 token 同样如实上报（幂等键挂 runId，重试须换新 runId）
            reportUsage(command, modelRef, usage.get());
        }
        return new ChatAgentReply(command.runId(), text.toString());
    }

    /** 工作区解析：带 workspaceId → 项目 dev 工作区；缺省 → 本地工作区（#44 口径）。 */
    private ChatAgentWorkspace resolveWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return new ChatAgentWorkspace.Local(properties.getWorkspace());
        }
        WorkspaceHandle handle = workspaceClient.handleOf(workspaceId);
        return new ChatAgentWorkspace.ProjectDev(workspaceId, handle.containerName());
    }

    private void handleEvent(io.agentscope.core.event.AgentEvent event,
            AgentscopeEventMapper mapper, Consumer<AgentEvent> sink, StringBuilder text,
            AtomicReference<TokenUsage> usage, AtomicReference<String> finish) {
        if (event instanceof TextBlockDeltaEvent delta) {
            text.append(delta.getDelta());
        }
        else if (event instanceof ModelCallEndEvent end) {
            usage.updateAndGet(total -> total.plus(AgentscopeUsageMapper.toTokenUsage(end.getUsage())));
        }
        mapper.finishToken(event).ifPresent(finish::set);
        AgentEvent frame = mapper.map(event);
        if (frame != null) {
            sink.accept(frame);
        }
    }

    private void reportUsage(ChatAgentCommand command, ModelRef modelRef, TokenUsage total) {
        if (command.usageContext() == null || total.total() <= 0) {
            return;
        }
        usageEventSink.report(new UsageEvent(
                USAGE_EVENT_PREFIX + command.runId(),
                clock.instant(),
                command.usageContext().subject(),
                command.runId(),
                command.sessionId(),
                modelRef.provider(),
                modelRef.modelId(),
                ENGINE,
                command.usageContext().dims(),
                total));
    }
}
