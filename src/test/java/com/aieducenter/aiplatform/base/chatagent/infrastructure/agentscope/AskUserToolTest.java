package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * {@link AskUserTool}（#48 挂起源）：工具自检恒 ASK（Built-in 不可绕过——trivial
 * 权限上下文也走自检，无需配置 permissionContext）；执行体返回 settle 注入的
 * answer（等待点答复通道写入 input 的键）。
 */
class AskUserToolTest {

    private final AskUserTool tool = new AskUserTool();

    @Test
    void given_no_answer_when_check_permissions_then_ask() {
        // 无答复 → ASK = 调用即挂起（RequireUserConfirmEvent → 平台等待点 QUESTION）
        io.agentscope.core.permission.PermissionDecision decision =
                tool.checkPermissions(Map.of("question", "用哪个框架?"), null).block();

        assertThat(decision.getBehavior())
                .isEqualTo(io.agentscope.core.permission.PermissionBehavior.ASK);
    }

    @Test
    void given_answer_injected_when_check_permissions_then_allow() {
        // 答复已注入（settle 重写 input 后重演）→ 放行执行（否则重演再挂起死循环）
        io.agentscope.core.permission.PermissionDecision decision = tool.checkPermissions(
                Map.of("question", "用哪个框架?", "answer", "Spring Boot"), null).block();

        assertThat(decision.getBehavior())
                .isEqualTo(io.agentscope.core.permission.PermissionBehavior.ALLOW);
    }

    @Test
    void given_answer_injected_when_called_then_answer_returned_as_result() {
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new io.agentscope.core.message.ToolUseBlock(
                        "tc-1", AskUserTool.NAME,
                        Map.of("question", "用哪个框架?", "answer", "Spring Boot"), null))
                .input(Map.of("question", "用哪个框架?", "answer", "Spring Boot"))
                .build();

        String result = ((io.agentscope.core.message.TextBlock) Mono.from(tool.callAsync(param))
                .block().getOutput().get(0)).getText();

        assertThat(result).isEqualTo("Spring Boot");
    }

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("ask_user");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        assertThat(tool.isReadOnly()).isTrue();
    }
}
