package com.aieducenter.aiplatform.base.agentengine.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * base.agentengine 错误定义（前缀 AGT_，ADR-0001 注册表）。
 */
public enum AgentEngineMessage implements CodeMessage {

    ENGINE_NOT_FOUND(404, "AGT_001", "开发智能体引擎不存在"),

    SESSION_NOT_FOUND(404, "AGT_002", "agent 会话不存在"),

    SESSION_WORKSPACE_MISMATCH(409, "AGT_003", "会话不属于该工作区"),

    ENGINE_REQUEST_FAILED(502, "AGT_004", "智能体引擎请求失败"),

    SESSION_FIELDS_INCOMPLETE(400, "AGT_005", "agent 会话字段不完整");

    private final int httpStatus;
    private final String code;
    private final String message;

    AgentEngineMessage(int httpStatus, String code, String message) {
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
