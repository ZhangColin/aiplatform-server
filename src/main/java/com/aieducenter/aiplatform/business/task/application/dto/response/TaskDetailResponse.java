package com.aieducenter.aiplatform.business.task.application.dto.response;

import java.util.List;

/**
 * 任务详情响应（opc/dev 共用 {@code GET /api/tasks/{taskId}}，A4 §6）：全量
 * 字段 + 最小项目上下文 + 项目 Bug 清单（复测表单的 bugId 来源——OPC 逐条
 * 出结果；dev 确认复审的对照面）。opc 侧 assignee=me 谓词在应用层守。
 */
public record TaskDetailResponse(
        TaskResponse task,
        TaskCardResponse.ProjectBrief project,
        List<BugResponse> bugs
) {
}
