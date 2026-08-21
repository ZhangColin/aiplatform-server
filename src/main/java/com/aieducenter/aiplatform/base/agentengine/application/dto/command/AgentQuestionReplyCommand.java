package com.aieducenter.aiplatform.base.agentengine.application.dto.command;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 问答答复命令：answers 按问题顺序，每项 = 该问题选中的标签列表（custom 输入也
 * 作为标签）——引擎协议原样（opencode /question/{id}/reply）。
 */
public record AgentQuestionReplyCommand(

        @NotNull(message = "answers 不能为空")
        @NotEmpty(message = "answers 不能为空列表")
        List<List<String>> answers) {
}
