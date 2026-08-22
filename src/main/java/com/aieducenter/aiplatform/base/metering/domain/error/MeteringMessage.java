package com.aieducenter.aiplatform.base.metering.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * base.metering 错误定义（前缀 METER_，ADR-0001 注册表预留位）。
 */
public enum MeteringMessage implements CodeMessage {

    TOKEN_USAGE_NEGATIVE(400, "METER_001", "token 用量不能为负数"),

    USAGE_EVENT_FIELDS_INCOMPLETE(400, "METER_002", "用量事件字段不完整"),

    USAGE_SUBJECT_REQUIRED(400, "METER_003", "用量查询必须指定 subject"),

    PRICE_ENTRY_FIELDS_INCOMPLETE(400, "METER_004", "单价行字段不完整"),

    PRICE_ENTRY_CLOSE_INVALID(400, "METER_005", "关行时点非法（空或早于生效起点）");

    private final int httpStatus;
    private final String code;
    private final String message;

    MeteringMessage(int httpStatus, String code, String message) {
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
