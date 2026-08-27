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

    SESSION_FIELDS_INCOMPLETE(400, "AGT_005", "agent 会话字段不完整"),

    WAIT_NOT_FOUND(404, "AGT_006", "等待点不存在"),

    /** 陈旧等待点答复（非 PENDING）或会话不可续跑——A1 §1.3 避雷「陈旧批准非法跳变」。 */
    WAIT_CONFLICT(409, "AGT_007", "等待点已关闭或会话不可续跑"),

    WAIT_FIELDS_INCOMPLETE(400, "AGT_008", "等待点字段不完整"),

    /** 后台切换入参不在注册表（票 #42）：PUT 值校验，400——区别于寻址语义的 404 AGT_001。 */
    ENGINE_CONFIG_UNKNOWN(400, "AGT_009", "未知的开发智能体引擎"),

    ENGINE_CONFIG_FIELDS_INCOMPLETE(400, "AGT_010", "引擎配置字段不完整"),

    /** 运行不可寻址（票 #38 运行终止）：runId 名下无等待点行且无会话最近运行匹配（或跨工作区）。 */
    RUN_NOT_FOUND(404, "AGT_011", "agent 运行不存在");

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
