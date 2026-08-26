package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import java.util.List;
import java.util.Map;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 向用户提问工具（#48）：对话智能体访谈式收集信息的挂起源——工具自检恒 ASK
 * （Built-in Checks 不可绕过、不受 permission mode/rules 影响），调用即触发
 * {@code RequireUserConfirmEvent} 挂起 → 平台等待点（QUESTION 载荷形状，见
 * {@link AgentscopeEventMapper} kind 判定）。用户 settle(answer) 后，答复文本由
 * 等待点答复通道注入工具 input 的 {@code answer} 键（ConfirmResult 允许携带修改后
 * 的 toolCall）——本工具执行即以答复作为工具结果回给模型，访谈继续。
 *
 * <p>无需配置 permissionContext（trivial 上下文也走工具自检），#45 既有对话路径
 * 行为零变化（未调用本工具不产生挂起）。</p>
 */
public class AskUserTool extends ToolBase {

    public static final String NAME = "ask_user";
    private static final String ANSWER_KEY = "answer";

    public AskUserTool() {
        super(ToolBase.builder()
                .name(NAME)
                .description("向用户提问并等待答复（访谈式收集信息；一次一个问题，选项可选）。"
                        + "调用后会挂起等待用户回答，答复将作为本工具的结果返回。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "question", Map.of(
                                        "type", "string",
                                        "description", "要问用户的问题"),
                                "header", Map.of(
                                        "type", "string",
                                        "description", "问题主题短标签（如「目标用户」，呈现于问答卡）"),
                                "options", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "候选选项（无可不填，用户可自由输入）"),
                                // 答复键：平台 settle 注入（等待点答复通道把用户答复
                                // 写进 input）——模型调用时不传
                                "answer", Map.of(
                                        "type", "string",
                                        "description", "用户答复（由平台在确认后注入，调用时不传）")),
                        "required", List.of("question")))
                .readOnly(true)
                .concurrencySafe(true));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 确认物 = 用户答复：无答复 → ASK 挂起（Built-in 不可绕过）；答复已注入
        // （settle 的 ConfirmResult 携带重写后的 input 重演）→ 放行执行。恒 ASK 会让
        // 批准后的重演再次挂起（权限自检在重演时重评估），死循环。
        if (toolInput != null && toolInput.containsKey(ANSWER_KEY)) {
            return Mono.just(PermissionDecision.allow("用户答复已注入，放行执行"));
        }
        return Mono.just(PermissionDecision.ask("向用户提问，等待答复"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 批准后的执行体：答复已由 settle 注入 input.answer（等待点答复通道），
        // 以之作为工具结果回给模型——访谈上下文直接可读
        Object answer = param.getInput() != null ? param.getInput().get(ANSWER_KEY) : null;
        return Mono.just(ToolResultBlock.text(answer != null ? String.valueOf(answer) : ""));
    }
}
