package com.aieducenter.aiplatform.business.task.application.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

import com.aieducenter.aiplatform.business.task.domain.enums.TaskStatus;
import com.aieducenter.aiplatform.business.task.domain.enums.TaskType;

/**
 * 任务响应（dev 项目任务面板，A4 §6）：全量字段含驳回理由与提交载荷
 * （确认/驳回的裁决输入）。枚举字段按编写规范以 Integer code 序列化，
 * xxxName 补显示名；submittedPayload 为载荷 JSON 的对象形态（null = 未提交）。
 */
public record TaskResponse(
        String taskId,
        String projectId,
        TaskType type,
        String typeName,
        String title,
        String content,
        Long assigneeAccountId,
        String assigneeName,
        TaskStatus status,
        String statusName,
        String waitId,
        Map<String, Object> submittedPayload,
        String rejectReason,
        LocalDateTime rejectedAt,
        LocalDateTime confirmedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
