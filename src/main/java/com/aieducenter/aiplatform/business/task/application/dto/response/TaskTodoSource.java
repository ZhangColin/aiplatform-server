package com.aieducenter.aiplatform.business.task.application.dto.response;

import java.time.Instant;

/**
 * 任务侧待办投影源（workbench 四型接线的查询面，A2 §4 / A4 §7）：task BC
 * 供出的状态事实，投影成什么待办（type/title）归 workbench。{@code since} 是
 * 源状态时刻（非拉取时刻）：SUBMITTED 取最近迁移（updatedAt）、NEW_TASK 取
 * 建任务时刻、TASK_REJECTED 取驳回时刻、RETEST_READY 取最后一条 FIXED 翻态
 * 时刻。
 */
public record TaskTodoSource(
        String taskId,
        String projectId,
        String title,
        Instant since
) {
}
