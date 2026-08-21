package com.aieducenter.aiplatform.base.agentengine.domain.model;

/**
 * 一次任务下发的命令（A1 §1.2）：systemPrompt 与 modelId 是入参——适配层零角色
 * 概念（角色卡在 business.project，B0 §1 拆解既定）。
 *
 * <p>可空缝：{@code sessionId} 非空 = 复用既有会话续跑（③ 任务完成回填用，续跑 =
 * 给会话的新消息，不是问答答复）；{@code usageContext} 非空 = run 级 UsageEvent
 * 的计量归属，底座透传不解释。{@code runId} 由任务端点（业务编排层 / 2a 底座任务
 * 端点）生成，底座透传——全部流事件以此关联。</p>
 */
public record AgentTaskCommand(
        String runId,
        String prompt,
        String systemPrompt,
        String modelId,
        String sessionId,
        UsageContext usageContext) {

    public AgentTaskCommand {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("AgentTaskCommand.runId 不能为空（任务端点生成）");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("AgentTaskCommand.prompt 不能为空");
        }
    }
}
