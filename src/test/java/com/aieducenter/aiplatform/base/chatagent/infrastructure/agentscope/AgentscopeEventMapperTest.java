package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
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

    @Nested
    class WaitFrames {

        @Test
        void confirm_event_maps_to_wait_raised_frame_with_contract_keys() {
            RequireUserConfirmEvent event = new RequireUserConfirmEvent("reply-9", java.util.List.of(
                    toolCall("tc-1", "write_file", Map.of("path", "docs/PRD.md"))));

            AgentEvent frame = mapper.waitRaised(event, resumeContext("deepseek:deepseek-v4-flash"));

            assertThat(frame.type()).isEqualTo(AgentEventTypes.WAIT_RAISED);
            assertThat(frame.payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.WAIT_RUN_FIELD, RUN_ID,
                    AgentEventTypes.WAIT_SESSION_FIELD, SESSION_ID,
                    AgentEventTypes.WAIT_KIND_FIELD, "PERMISSION",
                    AgentEventTypes.WAIT_SUMMARY_FIELD, "write_file",
                    AgentEventTypes.WAIT_ENGINE_REF_FIELD, "reply-9",
                    "engine", ENGINE));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) frame.payload()
                    .get(AgentEventTypes.WAIT_DATA_FIELD);
            // 引擎载荷原样：待确认工具清单 + 恢复私货（settle 侧据此重建 ConfirmResult）
            assertThat(data.get("toolCalls")).isEqualTo(java.util.List.of(
                    Map.of("id", "tc-1", "name", "write_file", "input", Map.of("path", "docs/PRD.md"))));
            assertThat(data.get("modelString")).isEqualTo("deepseek:deepseek-v4-flash");
            assertThat(data.get("userId")).isEqualTo("u-1");
            assertThat(data.get("usageContext")).isEqualTo(Map.of("subject", 42L, "dims", Map.of()));
            assertThat(data.get("streamCorrelation")).isEqualTo(Map.of("projectId", "42"));
        }

        @Test
        void ask_user_tool_maps_to_question_kind() {
            // 向用户提问（ask_user）= QUESTION 载荷形状；工具参数确认/敏感动作 = PERMISSION
            RequireUserConfirmEvent event = new RequireUserConfirmEvent("reply-10", java.util.List.of(
                    toolCall("tc-2", "ask_user", Map.of("question", "用哪个框架?"))));

            AgentEvent frame = mapper.waitRaised(event, resumeContext("deepseek:deepseek-v4-flash"));

            assertThat(frame.payload()).containsEntry(
                    AgentEventTypes.WAIT_KIND_FIELD, "QUESTION");
            // 摘要 = 问题文本（对齐 opencode 问答口径，非工具名）
            assertThat(frame.payload()).containsEntry(
                    AgentEventTypes.WAIT_SUMMARY_FIELD, "用哪个框架?");
        }

        @Test
        void ask_user_question_body_projects_pending_questions_shape() {
            // #40：QUESTION body 增 questions 投影（前端问答卡契约 header/question/
            // multiple/custom/options[{label}]——custom 必须显式 true，否则无选项题
            // 整题被前端丢弃）；toolCalls 恢复私货面不动（settle 侧仍按它重建）
            RequireUserConfirmEvent event = new RequireUserConfirmEvent("reply-14", java.util.List.of(
                    toolCall("tc-q", "ask_user", Map.of(
                            "header", "目标用户",
                            "question", "这个官网主要面向谁?",
                            "options", java.util.List.of("企业客户", "个人用户")))));

            AgentEvent frame = mapper.waitRaised(event, resumeContext("deepseek:deepseek-v4-flash"));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) frame.payload()
                    .get(AgentEventTypes.WAIT_DATA_FIELD);
            assertThat(data.get("questions")).isEqualTo(java.util.List.of(Map.of(
                    "header", "目标用户",
                    "question", "这个官网主要面向谁?",
                    "multiple", false,
                    "custom", true,
                    "options", java.util.List.of(
                            Map.of("label", "企业客户"), Map.of("label", "个人用户")))));
            assertThat(data.get("toolCalls")).isEqualTo(java.util.List.of(Map.of(
                    "id", "tc-q", "name", "ask_user",
                    "input", Map.of("header", "目标用户",
                            "question", "这个官网主要面向谁?",
                            "options", java.util.List.of("企业客户", "个人用户")))));
        }

        @Test
        void ask_user_without_header_or_options_still_answerable() {
            // header 缺省中性兜底、options 空 + custom=true：纯开放题前端仍可自由输入作答
            RequireUserConfirmEvent event = new RequireUserConfirmEvent("reply-15", java.util.List.of(
                    toolCall("tc-o", "ask_user", Map.of("question", "还有什么要补充的?"))));

            AgentEvent frame = mapper.waitRaised(event, resumeContext("deepseek:deepseek-v4-flash"));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) frame.payload()
                    .get(AgentEventTypes.WAIT_DATA_FIELD);
            assertThat(data.get("questions")).isEqualTo(java.util.List.of(Map.of(
                    "header", "提问",
                    "question", "还有什么要补充的?",
                    "multiple", false,
                    "custom", true,
                    "options", java.util.List.of())));
        }

        @Test
        void confirm_event_yields_no_passthrough_frame() {
            // 挂起不是过程帧：wait-raised 由调用方显式发射，map() 不重复产帧
            RequireUserConfirmEvent event = new RequireUserConfirmEvent("reply-11", java.util.List.of(
                    toolCall("tc-3", "write_file", Map.of())));

            assertThat(mapper.map(event)).isNull();
        }

        @Test
        void confirm_event_is_a_suspension_not_finish() {
            // 挂起轮的流终止不是终态：无结煞语、挂起标志可检出
            assertThat(mapper.finishToken(new RequireUserConfirmEvent("reply-12",
                    java.util.List.of(toolCall("tc-4", "write_file", Map.of()))))).isEmpty();
            assertThat(mapper.isSuspension(new RequireUserConfirmEvent("reply-13",
                    java.util.List.of(toolCall("tc-5", "write_file", Map.of()))))).isTrue();
            assertThat(mapper.isSuspension(new TextBlockDeltaEvent("r", "b", "d"))).isFalse();
        }

        private io.agentscope.core.message.ToolUseBlock toolCall(String id, String name,
                Map<String, Object> input) {
            return new io.agentscope.core.message.ToolUseBlock(id, name, input);
        }

        private Map<String, Object> resumeContext(String modelString) {
            return Map.of(
                    "modelString", modelString,
                    "userId", "u-1",
                    "usageContext", Map.of("subject", 42L, "dims", Map.of()),
                    "streamCorrelation", Map.of("projectId", "42"));
        }
    }
}
