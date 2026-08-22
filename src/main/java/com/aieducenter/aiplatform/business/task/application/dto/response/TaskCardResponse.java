package com.aieducenter.aiplatform.business.task.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.task.domain.enums.TaskStatus;

/**
 * 任务卡片响应（opc 指派清单 {@code GET /api/tasks}，A4 §6/§7）：assignee=me
 * 跨项目，卡片带**最小项目上下文**（项目名 + 预览地址必带——OPC 测试要看
 * 预览；PRD 全文 v1 留缝）。驳回理由带上——被驳回重做是卡片常态。
 */
public record TaskCardResponse(
        String taskId,
        String projectId,
        ProjectBrief project,
        String title,
        String content,
        TaskStatus status,
        String statusName,
        String rejectReason,
        LocalDateTime rejectedAt,
        LocalDateTime createdAt
) {

    /**
     * 最小项目上下文（A4 §7）：项目名 + 预览地址。
     */
    public record ProjectBrief(
            String name,
            String previewUrl
    ) {
    }
}
