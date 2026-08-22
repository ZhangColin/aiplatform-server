package com.aieducenter.aiplatform.business.task.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * business.task 错误定义（前缀 TASK_，ADR-0001 已预留位）。
 */
public enum TaskMessage implements CodeMessage {

    TASK_FIELDS_INCOMPLETE(400, "TASK_001", "任务字段不完整（标题/内容/指派/项目）"),

    ILLEGAL_TRANSITION(409, "TASK_002", "任务状态不允许该操作（{0} → {1}）"),

    REJECT_REASON_REQUIRED(400, "TASK_003", "驳回理由必填"),

    NOT_ASSIGNEE(403, "TASK_004", "仅指派本人可操作该任务"),

    BUG_NOT_FOUND(404, "TASK_005", "Bug 不存在"),

    SUBMIT_PAYLOAD_INVALID(400, "TASK_006", "提交载荷不合法（report 必填；bugs 与 results 二选一）"),

    TASK_NOT_FOUND(404, "TASK_007", "任务不存在"),

    ASSIGNEE_NOT_FOUND(404, "TASK_008", "指派账号不存在"),

    TASK_NOT_OWNER(403, "TASK_009", "仅项目归属账号可执行该操作（dev 动作）"),

    BUG_CLOSE_REASON_REQUIRED(400, "TASK_010", "Bug 关闭理由必填");

    private final int httpStatus;
    private final String code;
    private final String message;

    TaskMessage(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
