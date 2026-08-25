package com.aieducenter.aiplatform.base.chatagent.domain.model;

import com.aieducenter.aiplatform.base.chatagent.domain.error.ChatAgentMessage;
import com.cartisan.core.exception.DomainException;

/**
 * 一轮对话命令（#44）：平台进程内 HarnessAgent 单轮调用的全部入参。
 *
 * <p>{@code runId} 为本轮调用的平台标识（计量幂等键基于它）；{@code systemPrompt} /
 * {@code modelString} 可空——为空时取适配器配置默认；{@code usageContext} 可空——
 * 为空则本轮不上报用量（底座不发明归属）。</p>
 */
public record ChatAgentCommand(
        String runId,
        String prompt,
        String systemPrompt,
        String modelString,
        String sessionId,
        String userId,
        UsageContext usageContext) {

    public ChatAgentCommand {
        if (runId == null || runId.isBlank() || prompt == null || prompt.isBlank()
                || sessionId == null || sessionId.isBlank()) {
            throw new DomainException(ChatAgentMessage.COMMAND_FIELDS_INCOMPLETE,
                    "runId/prompt/sessionId 必填");
        }
    }
}
