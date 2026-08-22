package com.aieducenter.aiplatform.business.task.endpoints.controller;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.task.domain.error.TaskMessage;

/**
 * 任务 REST 寻址解析：taskId 路径段（TSID 十进制字符串）→ Long。非数值/非正数
 * 即不存在的标识，语义上同 404（与 ProjectIds 口径一致）。
 */
final class TaskIds {

    private TaskIds() {
    }

    static Long parse(String taskId) {
        try {
            long parsed = Long.parseLong(taskId);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 非数值 → 落到下方统一 404
        }
        throw new ApplicationException(TaskMessage.TASK_NOT_FOUND);
    }
}
