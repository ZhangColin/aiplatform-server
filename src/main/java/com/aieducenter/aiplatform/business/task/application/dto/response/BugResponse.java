package com.aieducenter.aiplatform.business.task.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.task.domain.enums.BugSeverity;
import com.aieducenter.aiplatform.business.task.domain.enums.BugStatus;

/**
 * Bug 响应（dev Bug 面板 {@code GET /api/projects/{id}/bugs}，A4 §6）：状态 /
 * fix_run_id / fix_note / closed_reason 全带（修复编排链 #27 的呈现面先就位）。
 */
public record BugResponse(
        String bugId,
        String projectId,
        String sourceTaskId,
        String title,
        String description,
        String reproSteps,
        BugSeverity severity,
        String severityName,
        BugStatus status,
        String statusName,
        String fixRunId,
        String fixNote,
        String closedReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
