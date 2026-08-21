package com.aieducenter.aiplatform.base.workspace.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * base.workspace 错误定义（前缀 WSP_，ADR-0001 注册表）。
 */
public enum WorkspaceMessage implements CodeMessage {

    WORKSPACE_NOT_FOUND(404, "WSP_001", "工作区不存在"),

    ENVIRONMENT_OPERATION_FAILED(500, "WSP_002", "环境后端操作失败"),

    PORT_ALLOCATION_FAILED(500, "WSP_003", "无法为工作区分配宿主机端口"),

    WORKSPACE_ID_INVALID(400, "WSP_004", "工作区标识不合法"),

    WORKSPACE_FIELDS_INCOMPLETE(400, "WSP_005", "工作区字段不完整"),

    RESOURCE_FIELDS_INCOMPLETE(400, "WSP_006", "中间件资源字段不完整"),

    ENVIRONMENT_KIND_NOT_SUPPORTED(400, "WSP_007", "暂不支持的环境类型（Phase A 仅 DEV）");

    private final int httpStatus;
    private final String code;
    private final String message;

    WorkspaceMessage(int httpStatus, String code, String message) {
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
