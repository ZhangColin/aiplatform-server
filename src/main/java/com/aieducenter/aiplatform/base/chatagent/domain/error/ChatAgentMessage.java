package com.aieducenter.aiplatform.base.chatagent.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * base.chatagent 错误定义（前缀 CHAT_，ADR-0001 注册表）。
 */
public enum ChatAgentMessage implements CodeMessage {

    MODEL_REF_INVALID(400, "CHAT_001", "模型串非法（应为 provider:model，当前支持 deepseek）"),

    COMMAND_FIELDS_INCOMPLETE(400, "CHAT_002", "对话命令字段不完整"),

    CONVERSE_FAILED(502, "CHAT_003", "对话智能体调用失败");

    private final int httpStatus;
    private final String code;
    private final String message;

    ChatAgentMessage(int httpStatus, String code, String message) {
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
