package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aieducenter.aiplatform.base.chatagent.domain.error.ChatAgentMessage;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentProgress;
import com.aieducenter.aiplatform.base.chatagent.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.cartisan.core.exception.DomainException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

/**
 * {@link AgentscopeChatAgentClient}（#44）：流式文本回调、RuntimeContext 组装、
 * 模型调用事件 → UsageEvent（恰一条、幂等键 chat-usage-{runId}、engine=agentscope）。
 */
@ExtendWith(MockitoExtension.class)
class AgentscopeChatAgentClientTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AgentscopeHarnessAgentFactory factory;

    @Mock
    private HarnessAgent agent;

    @Mock
    private UsageEventSink usageEventSink;

    @Captor
    private ArgumentCaptor<UsageEvent> usageCaptor;

    @Captor
    private ArgumentCaptor<RuntimeContext> contextCaptor;

    private ChatAgentProperties properties;

    private AgentscopeChatAgentClient client;

    @BeforeEach
    void setUp() {
        properties = new ChatAgentProperties();
        properties.setAgentName("chat-agent");
        properties.setDefaultModel("deepseek:deepseek-v4-flash");
        properties.setDefaultSystemPrompt("你是平台对话智能体。");
        properties.setTimeout(Duration.ofSeconds(30));
        client = new AgentscopeChatAgentClient(factory, properties, usageEventSink, CLOCK);
    }

    private ChatAgentCommand command(String modelString, UsageContext usage) {
        return new ChatAgentCommand("run-1", "你好", null, modelString, "s-1", "alice", usage);
    }

    private void givenStream(AgentEvent... events) {
        when(factory.obtain(any(), any(), any(), any())).thenReturn(agent);
        when(agent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.fromIterable(List.of(events)));
    }

    @Test
    void given_streaming_text_deltas_when_converse_then_each_delta_forwarded_and_text_accumulated() {
        givenStream(
                new TextBlockDeltaEvent("r-1", "b-1", "你"),
                new TextBlockDeltaEvent("r-1", "b-1", "好"),
                new TextBlockDeltaEvent("r-1", "b-1", "呀"));

        List<String> deltas = new ArrayList<>();
        var reply = client.converse(command(null, null), deltas::add);

        assertThat(deltas).containsExactly("你", "好", "呀");
        assertThat(reply.runId()).isEqualTo("run-1");
        assertThat(reply.text()).isEqualTo("你好呀");
    }

    @Test
    void given_converse_when_call_agent_then_runtime_context_carries_session_and_user() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "嗯"));

        client.converse(command(null, null), ChatAgentProgress.NONE);

        verify(agent).streamEvents(any(UserMessage.class), contextCaptor.capture());
        assertThat(contextCaptor.getValue().getSessionId()).isEqualTo("s-1");
        assertThat(contextCaptor.getValue().getUserId()).isEqualTo("alice");
    }

    @Test
    void given_multiple_model_call_ends_when_converse_then_usage_summed_and_reported_once() {
        givenStream(
                new ModelCallEndEvent("r-1", new ChatUsage(100, 40, 20, 0.5)),
                new TextBlockDeltaEvent("r-1", "b-1", "答"),
                new ModelCallEndEvent("r-1", new ChatUsage(60, 10, 0, 0.2)));

        client.converse(command("deepseek:deepseek-chat",
                        new UsageContext("prj-1", Map.of("role", "BA"))),
                ChatAgentProgress.NONE);

        verify(usageEventSink).report(usageCaptor.capture());
        UsageEvent event = usageCaptor.getValue();
        assertThat(event.eventId()).isEqualTo("chat-usage-run-1");
        assertThat(event.ts()).isEqualTo(Instant.parse("2026-08-25T10:00:00Z"));
        assertThat(event.subject()).isEqualTo("prj-1");
        assertThat(event.runId()).isEqualTo("run-1");
        assertThat(event.sessionId()).isEqualTo("s-1");
        assertThat(event.provider()).isEqualTo("deepseek");
        assertThat(event.model()).isEqualTo("deepseek-chat");
        assertThat(event.engine()).isEqualTo("agentscope");
        assertThat(event.dims()).containsEntry("role", "BA");
        assertThat(event.tokens().input()).isEqualTo(140);
        assertThat(event.tokens().output()).isEqualTo(50);
        assertThat(event.tokens().cacheRead()).isEqualTo(20);
        assertThat(event.tokens().total()).isPositive();
    }

    @Test
    void given_no_usage_context_when_converse_then_metering_skipped() {
        givenStream(new ModelCallEndEvent("r-1", new ChatUsage(10, 5, 0, 0.1)));

        client.converse(command(null, null), ChatAgentProgress.NONE);

        verifyNoInteractions(usageEventSink);
    }

    @Test
    void given_no_model_call_end_when_converse_then_zero_usage_not_reported() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "空"));

        client.converse(command(null, new UsageContext("prj-1", Map.of())),
                ChatAgentProgress.NONE);

        verifyNoInteractions(usageEventSink);
    }

    @Test
    void given_stream_error_when_converse_then_wrapped_as_domain_exception() {
        when(factory.obtain(any(), any(), any(), any())).thenReturn(agent);
        when(agent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new RuntimeException("boom")));

        assertThatThrownBy(() -> client.converse(command(null, null), ChatAgentProgress.NONE))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ChatAgentMessage.CONVERSE_FAILED.message());
    }

    @Test
    void given_stream_error_after_model_call_when_converse_then_consumed_usage_still_reported() {
        when(factory.obtain(any(), any(), any(), any())).thenReturn(agent);
        when(agent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.concat(
                        Flux.just(new ModelCallEndEvent("r-1", new ChatUsage(100, 40, 0, 0.5))),
                        Flux.error(new RuntimeException("mid-stream boom"))));

        assertThatThrownBy(() -> client.converse(
                        command(null, new UsageContext("prj-1", Map.of())),
                        ChatAgentProgress.NONE))
                .isInstanceOf(DomainException.class);

        verify(usageEventSink).report(usageCaptor.capture());
        assertThat(usageCaptor.getValue().tokens().input()).isEqualTo(100);
        assertThat(usageCaptor.getValue().tokens().output()).isEqualTo(40);
    }

    @Test
    void given_no_model_string_when_converse_then_configured_default_applied() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "默认"));

        client.converse(command(null, null), ChatAgentProgress.NONE);

        verify(factory).obtain(eq("chat-agent"), eq("你是平台对话智能体。"),
                eq("deepseek:deepseek-v4-flash"), any());
    }
}
