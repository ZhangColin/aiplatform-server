package com.aieducenter.aiplatform.base.chatagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.chatagent.domain.port.ChatAgentClient;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope.AgentscopeChatAgentClient;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope.AgentscopeChatAgentClient.ChatAgentResume;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope.ChatAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;

/**
 * #48 真内核 HITL 冒烟（DEEPSEEK_API_KEY 未设整类跳过）：真 AgentScope 挂起 →
 * settle 语义续跑 → 访谈上下文跨轮延续。挂起轮断言 wait-raised 软终点（无
 * task-finish）+ AgentState 已落 PG（重启锚）；续跑以等待点 body 同构形状重建
 * ConfirmResult（JSON 落库往返——桥的 body 恢复路径）；第二轮对话验证上下文
 * （平台桥落库/寻址面由 ChatAgentWaitFlowIntegrationTest 覆盖）。
 */
@SpringBootTest
class ChatAgentHitlSmokeTest {

    private static final String SESSION = "smoke-hitl-" + UUID.randomUUID();
    private static final String USER = "smoke-hitl-user";
    private static final String ANSWER = "甲号方案";

    @TempDir
    static java.nio.file.Path workspace;

    @Autowired
    private AgentscopeChatAgentClient chatAgentClient;

    @Autowired
    private ChatAgentProperties chatAgentProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private java.nio.file.Path workspaceBackup;

    @BeforeAll
    static void requireDeepseekKey() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        org.junit.jupiter.api.Assumptions.assumeTrue(key != null && !key.isBlank(),
                "DEEPSEEK_API_KEY 未设置，跳过真内核 HITL 冒烟");
    }

    @AfterEach
    void restoreAndClean() {
        if (workspaceBackup != null) {
            chatAgentProperties.setWorkspace(workspaceBackup);
        }
        jdbcTemplate.update("DELETE FROM cat_agent_state WHERE user_id = ?", USER);
        jdbcTemplate.update("DELETE FROM met_usage_events WHERE session_id = ?", SESSION);
    }

    @Test
    void given_ask_user_invoked_when_suspended_then_resumes_and_context_survives() throws Exception {
        chatAgentProperties.setWorkspace(workspace);
        List<AgentEvent> frames = new ArrayList<>();

        // 1) 挂起轮：指示模型调用 ask_user 提问（挂起源 = 工具自检恒 ASK）
        chatAgentClient.converse(new ChatAgentCommand(
                "smoke-hitl-run-" + UUID.randomUUID(),
                "请立即调用 ask_user 工具向我提问，question 填「选哪个方案？」，"
                        + "options 填 [\"甲号方案\", \"乙号方案\"]。不要直接回答。",
                null, null, SESSION, USER,
                new UsageContext("smoke-prj", Map.of("scene", "hitl-smoke")),
                null, Map.of()), sawLast(frames));

        AgentEvent wait = frames.stream()
                .filter(f -> AgentEventTypes.WAIT_RAISED.equals(f.type())).findFirst()
                .orElseThrow(() -> new AssertionError("未见挂起帧，实际帧序："
                        + frames.stream().map(AgentEvent::type).toList()));
        // 软终点：挂起轮不发 task-finish（run 等 settle 续跑后才收口）
        assertThat(frames.stream().map(AgentEvent::type))
                .doesNotContain(AgentEventTypes.TASK_FINISH);
        assertThat(wait.payload()).containsEntry(AgentEventTypes.WAIT_KIND_FIELD, "QUESTION");
        // AgentState 已落 PG（会话恢复锚——重启后按会话标识可恢复）
        Integer stateRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cat_agent_state WHERE user_id = ? AND session_id = ?",
                Integer.class, USER, SESSION);
        assertThat(stateRows).isPositive();

        // 2) settle 续跑：等待点 body 的恢复私货走 JSON 落库往返（桥的恢复路径同构）
        Map<String, Object> body = objectMapper.readValue(
                objectMapper.writeValueAsString(
                        wait.payload().get(AgentEventTypes.WAIT_DATA_FIELD)),
                Map.class);
        ChatAgentResume resume = resumeFrom(body, wait);
        List<AgentEvent> resumeFrames = new ArrayList<>();
        chatAgentClient.resume(resume, sawLast(resumeFrames));

        // 续跑流收口（task-finish 到达 = run 终态；AgentScope 拿到答复继续）
        assertThat(resumeFrames.stream().map(AgentEvent::type))
                .as("续跑帧序：{}", resumeFrames.stream().map(f -> f.type() + f.payload()).toList())
                .contains(AgentEventTypes.TASK_FINISH);

        // 3) 访谈上下文跨轮延续（同会话新 run 读到挂起前的对话与答复）
        List<AgentEvent> secondRun = new ArrayList<>();
        chatAgentClient.converse(new ChatAgentCommand(
                "smoke-hitl-run2-" + UUID.randomUUID(),
                "我在上一轮的提问里选了什么方案？只回答方案名。",
                null, null, SESSION, USER, null, null, Map.of()), sawLast(secondRun));
        String secondText = String.join("", secondRun.stream()
                .filter(f -> "text".equals(f.type()))
                .map(f -> textOf(f)).toList());
        assertThat(secondText).contains("甲");
    }

    // ---------- 内部 ----------

    /**
     * 等待点 body（恢复私货）→ 续跑请求：答复注入工具 input 的 answer 键（答复通道
     * 同构）。content 回填 input 的 JSON 串——重放参数校验只认 content 原文，null 会
     * 炸校验错误结果给模型（#51 根因，与 AgentscopeWaitResponder.askingToolCall 同口径）。
     */
    @SuppressWarnings("unchecked")
    private ChatAgentResume resumeFrom(Map<String, Object> body, AgentEvent wait) {
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) body.get("toolCalls");
        List<ConfirmResult> confirmResults = toolCalls.stream()
                .map(tc -> {
                    Map<String, Object> input = new java.util.LinkedHashMap<>(
                            (Map<String, Object>) tc.get("input"));
                    input.put("answer", ANSWER);
                    return new ConfirmResult(true, new ToolUseBlock(
                            String.valueOf(tc.get("id")), String.valueOf(tc.get("name")),
                            input, jsonOf(input), null,
                            io.agentscope.core.message.ToolCallState.ASKING));
                })
                .toList();
        return new ChatAgentResume(
                String.valueOf(wait.payload().get(AgentEventTypes.WAIT_RUN_FIELD)),
                SESSION, USER, null,
                blankToNull(String.valueOf(body.getOrDefault("modelString", ""))),
                blankToNull(String.valueOf(body.getOrDefault("systemPrompt", ""))),
                String.valueOf(wait.payload().get(AgentEventTypes.WAIT_ENGINE_REF_FIELD)),
                confirmResults, ANSWER, null, Map.of());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? null : value;
    }

    private String jsonOf(Map<String, Object> input) {
        try {
            return objectMapper.writeValueAsString(input);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("待确认工具参数序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String textOf(AgentEvent frame) {
        Object data = frame.payload().get("data");
        Object delta = data instanceof Map<?, ?> map ? map.get("delta") : null;
        return delta != null ? String.valueOf(delta) : "";
    }

    /** sink 收尾帧覆盖（最后一帧是软终点或终态，列表保全过程帧）。 */
    private static Consumer<AgentEvent> sawLast(List<AgentEvent> frames) {
        return frames::add;
    }
}
