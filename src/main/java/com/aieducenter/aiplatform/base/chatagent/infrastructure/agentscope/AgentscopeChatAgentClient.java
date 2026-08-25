package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import com.aieducenter.aiplatform.base.chatagent.domain.error.ChatAgentMessage;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentProgress;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ModelRef;
import com.aieducenter.aiplatform.base.chatagent.domain.port.ChatAgentClient;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link ChatAgentClient} 的 AgentScope 实现（#44 最小骨架）：平台进程内 HarnessAgent
 * 一轮对话——TextBlock 增量回调 progress，模型调用事件（ReAct 每迭代一条
 * ModelCallEnd）五桶累积，对话结束（含失败轮，已耗 token 如实计量）按命令的
 * usageContext 上报恰一条 UsageEvent（幂等键 chat-usage-{runId}，engine=agentscope；
 * 归属为空不发明、零用量不报）。
 */
@Component
@Adapter(PortType.CLIENT)
public class AgentscopeChatAgentClient implements ChatAgentClient {

    private static final String USAGE_EVENT_PREFIX = "chat-usage-";
    private static final String ENGINE = "agentscope";

    private final AgentscopeHarnessAgentFactory factory;
    private final ChatAgentProperties properties;
    private final UsageEventSink usageEventSink;
    private final Clock clock;

    @Autowired
    public AgentscopeChatAgentClient(AgentscopeHarnessAgentFactory factory,
            ChatAgentProperties properties, UsageEventSink usageEventSink) {
        this(factory, properties, usageEventSink, Clock.systemUTC());
    }

    AgentscopeChatAgentClient(AgentscopeHarnessAgentFactory factory,
            ChatAgentProperties properties, UsageEventSink usageEventSink, Clock clock) {
        this.factory = factory;
        this.properties = properties;
        this.usageEventSink = usageEventSink;
        this.clock = clock;
    }

    @Override
    public ChatAgentReply converse(ChatAgentCommand command, ChatAgentProgress progress) {
        ModelRef modelRef = ModelRef.parse(command.modelString() != null
                ? command.modelString() : properties.getDefaultModel());
        String sysPrompt = command.systemPrompt() != null
                ? command.systemPrompt() : properties.getDefaultSystemPrompt();

        HarnessAgent agent = factory.obtain(properties.getAgentName(), sysPrompt,
                modelRef.toModelString(), properties.getWorkspace());
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(command.sessionId())
                .userId(command.userId())
                .build();

        StringBuilder text = new StringBuilder();
        AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.ZERO);
        try {
            agent.streamEvents(new UserMessage(command.prompt()), ctx)
                    .doOnNext(event -> handleEvent(event, progress, text, usage))
                    .blockLast(properties.getTimeout());
        }
        catch (Exception e) {
            throw new DomainException(ChatAgentMessage.CONVERSE_FAILED, e,
                    "runId=" + command.runId() + ", model=" + modelRef.toModelString());
        }
        finally {
            // 失败轮已消耗的 token 同样如实上报（幂等键挂 runId，重试须换新 runId）
            reportUsage(command, modelRef, usage.get());
        }
        return new ChatAgentReply(command.runId(), text.toString());
    }

    private void handleEvent(AgentEvent event, ChatAgentProgress progress, StringBuilder text,
            AtomicReference<TokenUsage> usage) {
        if (event instanceof TextBlockDeltaEvent delta) {
            text.append(delta.getDelta());
            progress.onTextDelta(delta.getDelta());
        }
        else if (event instanceof ModelCallEndEvent end) {
            usage.updateAndGet(total -> total.plus(AgentscopeUsageMapper.toTokenUsage(end.getUsage())));
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
