package com.aieducenter.aiplatform.base.chatagent.domain.model;

import com.aieducenter.aiplatform.base.chatagent.domain.error.ChatAgentMessage;
import com.aieducenter.aiplatform.base.eventhub.domain.model.EventEnvelope;
import com.cartisan.core.exception.DomainException;
import java.util.Map;

/**
 * 一轮对话命令（#44 建，#45 补工作区与流关联）：平台进程内 HarnessAgent 单轮调用的
 * 全部入参。
 *
 * <p>{@code runId} 为本轮调用的平台标识（计量幂等键与流帧锚定都基于它）；
 * {@code systemPrompt} / {@code modelString} 可空——为空时取适配器配置默认；
 * {@code usageContext} 可空——为空则本轮不上报用量（底座不发明归属）；
 * {@code workspaceId} 可空——为空落适配器配置的本地工作区（#44 口径），带值则
 * 解析为项目 dev 工作区（对话智能体读写项目文件，BA 写 docs/PRD.md 的基础）；
 * {@code streamCorrelation} 可空——流关联字段（如 projectId，对齐编码引擎 run 的
 * AgentRunContext 口径：底座不解释，逐帧注入 agent 流 payload）。</p>
 */
public record ChatAgentCommand(
        String runId,
        String prompt,
        String systemPrompt,
        String modelString,
        String sessionId,
        String userId,
        UsageContext usageContext,
        String workspaceId,
        Map<String, Object> streamCorrelation) {

    public ChatAgentCommand {
        if (runId == null || runId.isBlank() || prompt == null || prompt.isBlank()
                || sessionId == null || sessionId.isBlank()) {
            throw new DomainException(ChatAgentMessage.COMMAND_FIELDS_INCOMPLETE,
                    "runId/prompt/sessionId 必填");
        }
        streamCorrelation = streamCorrelation == null ? Map.of() : Map.copyOf(streamCorrelation);
        if (streamCorrelation.containsKey(EventEnvelope.TYPE_KEY)) {
            throw new DomainException(ChatAgentMessage.COMMAND_FIELDS_INCOMPLETE,
                    "streamCorrelation 禁含 " + EventEnvelope.TYPE_KEY + " 键（信封契约）");
        }
    }
}
