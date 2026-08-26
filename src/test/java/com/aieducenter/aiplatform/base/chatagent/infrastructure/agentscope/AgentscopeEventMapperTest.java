package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentscopeEventMapper} 单点映射表（#45 事件桥正本）：AgentScope 事件 →
 * 平台 agent 流事件帧。每类映射断言 type + payload 形状（对齐 SSE事件清单·通道二
 * 引擎透传口径：runId/sessionId/engine/data）。
 */
class AgentscopeEventMapperTest {

    private static final String RUN_ID = "r1";
    private static final String SESSION_ID = "s1";
    private static final String ENGINE = "agentscope";

    private final AgentscopeEventMapper mapper = new AgentscopeEventMapper(RUN_ID, SESSION_ID, ENGINE);

    @Nested
    class ProcessEvents {

        @Test
        void text_delta_maps_to_text_frame_with_delta_payload() {
            AgentEvent frame = mapper.map(new TextBlockDeltaEvent("reply-1", "b-1", "你好"));

            assertThat(frame).isNotNull();
            assertThat(frame.type()).isEqualTo("text");
            assertThat(frame.payload()).containsAllEntriesOf(Map.of(
                    "runId", RUN_ID, "sessionId", SESSION_ID, "engine", ENGINE));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) frame.payload().get("data");
            assertThat(data).containsOnly(
                    Map.entry("delta", "你好"), Map.entry("blockId", "b-1"));
        }

        @Test
        void thinking_delta_maps_to_reasoning_frame() {
            AgentEvent frame = mapper.map(new ThinkingBlockDeltaEvent("reply-1", "b-2", "想一想"));

            assertThat(frame.type()).isEqualTo("reasoning");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) frame.payload().get("data");
            assertThat(data).containsEntry("delta", "想一想");
        }

        @Test
        void tool_call_start_and_end_map_to_tool_frames_with_phase() {
            AgentEvent start = mapper.map(new ToolCallStartEvent("reply-1", "tc-1", "write_file"));
            AgentEvent end = mapper.map(new ToolCallEndEvent("reply-1", "tc-1", "write_file"));

            assertThat(start.type()).isEqualTo("tool");
            assertThat(end.type()).isEqualTo("tool");
            @SuppressWarnings("unchecked")
            Map<String, Object> startData = (Map<String, Object>) start.payload().get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> endData = (Map<String, Object>) end.payload().get("data");
            assertThat(startData).containsOnly(
                    Map.entry("toolCallId", "tc-1"),
                    Map.entry("toolName", "write_file"),
                    Map.entry("phase", "start"));
            assertThat(endData).containsOnly(
                    Map.entry("toolCallId", "tc-1"),
                    Map.entry("toolName", "write_file"),
                    Map.entry("phase", "end"));
        }

        @Test
        void model_call_boundaries_map_to_step_frames() {
            AgentEvent start = mapper.map(new ModelCallStartEvent("reply-1"));
            AgentEvent end = mapper.map(new ModelCallEndEvent("reply-1", null));

            assertThat(start.type()).isEqualTo("step-start");
            assertThat(end.type()).isEqualTo("step-finish");
            @SuppressWarnings("unchecked")
            Map<String, Object> endData = (Map<String, Object>) end.payload().get("data");
            assertThat(endData).containsKey("replyId");
        }

        @Test
        void unmapped_events_are_skipped() {
            // 边界/块尾/结果等未映射类型不产帧（扩展点：HITL 类归 #48）
            assertThat(mapper.map(new TextBlockEndEvent("reply-1", "b-1"))).isNull();
            assertThat(mapper.map(new AgentEndEvent("reply-1"))).isNull();
            assertThat(mapper.map(new AgentResultEvent((Msg) null))).isNull();
        }
    }

    @Nested
    class LifecycleFrames {

        @Test
        void task_start_carries_prompt_and_model() {
            AgentEvent frame = AgentscopeEventMapper.taskStart(RUN_ID, "写个 PRD", "deepseek:m-1", ENGINE);

            assertThat(frame.type()).isEqualTo(AgentEventTypes.TASK_START);
            assertThat(frame.payload()).containsOnly(
                    Map.entry("runId", RUN_ID),
                    Map.entry("prompt", "写个 PRD"),
                    Map.entry("model", "deepseek:m-1"),
                    Map.entry("engine", ENGINE));
        }

        @Test
        void session_created_carries_session_id() {
            AgentEvent frame = AgentscopeEventMapper.sessionCreated(RUN_ID, SESSION_ID, ENGINE);

            assertThat(frame.type()).isEqualTo(AgentEventTypes.SESSION_CREATED);
            assertThat(frame.payload()).containsOnly(
                    Map.entry("runId", RUN_ID),
                    Map.entry("sessionId", SESSION_ID),
                    Map.entry("engine", ENGINE));
        }

        @Test
        void task_finish_carries_finish_token() {
            AgentEvent frame = AgentscopeEventMapper.taskFinish(RUN_ID, SESSION_ID, "end", ENGINE);

            assertThat(frame.type()).isEqualTo(AgentEventTypes.TASK_FINISH);
            assertThat(frame.payload()).containsOnly(
                    Map.entry("runId", RUN_ID),
                    Map.entry("sessionId", SESSION_ID),
                    Map.entry("engine", ENGINE),
                    Map.entry("finish", "end"));
        }

        @Test
        void error_carries_message() {
            AgentEvent frame = AgentscopeEventMapper.error(RUN_ID, "模型超时");

            assertThat(frame.type()).isEqualTo(AgentEventTypes.ERROR);
            assertThat(frame.payload()).containsOnly(
                    Map.entry("runId", RUN_ID),
                    Map.entry("message", "模型超时"));
        }
    }

    @Nested
    class FinishToken {

        @Test
        void exceed_max_iters_is_a_terminal_finish_token() {
            assertThat(mapper.finishToken(new ExceedMaxItersEvent("reply-1", 10, 10)))
                    .contains("exceed_max_iters");
        }

        @Test
        void other_events_have_no_finish_token() {
            assertThat(mapper.finishToken(new TextBlockDeltaEvent("r", "b", "d"))).isEmpty();
            assertThat(mapper.finishToken(new AgentEndEvent("reply-1"))).isEmpty();
        }
    }
}
