package com.aieducenter.aiplatform.business.project.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * Project Context 错误码（前缀 {@code PRJ_}，ADR-0001 注册表既定位）。
 */
public enum ProjectMessage implements CodeMessage {

    PROJECT_NOT_FOUND(404, "PRJ_001", "项目不存在"),

    // PRJ_002 曾是「未知的开发智能体引擎」（建项目显式 engine 校验），#39 创建精简
    // 后引擎唯一通道 = 全局配置（配置侧校验归 AGT_ 域），码位注销不复用

    ROLE_UNKNOWN(400, "PRJ_003", "未知的角色卡"),

    ROLE_REQUIRED(409, "PRJ_004", "当前阶段无默认角色，需显式指定角色卡"),

    PROJECT_NAME_BLANK(400, "PRJ_005", "项目名不能为空白"),

    PROJECT_FIELDS_INCOMPLETE(400, "PRJ_006", "项目字段不完整"),

    GATE_TASKS_INSUFFICIENT(409, "PRJ_007", "门禁不足：本阶段完成任务数未达门限"),

    GATE_OPEN_BUGS(409, "PRJ_008", "开发完成确认未通过：项目存在未关闭 Bug"),

    STAGE_NO_GATE(409, "PRJ_009", "当前阶段无确认门（无门段的推进由编排触发，不是人拍板）"),

    ITERATION_NOT_OPEN(409, "PRJ_010", "项目无进行中的期（主链已收口或未初始化）"),

    REJECT_REASON_REQUIRED(400, "PRJ_011", "驳回理由必填"),

    DEMAND_CONTENT_BLANK(400, "PRJ_012", "需求池内容不能为空白"),

    PROJECT_ALREADY_ARCHIVED(409, "PRJ_013", "项目已归档（归档是单向终点）"),

    PROJECT_FILTER_UNKNOWN(400, "PRJ_014", "无效的项目列表状态过滤参数"),

    /** #41：PRD 读端点的「未产出」口径（工作区无 docs/PRD.md）——区别于项目不存在的 PRJ_001。 */
    PRD_NOT_PRODUCED(404, "PRJ_015", "PRD 尚未产出"),

    /** #49：G1（需求确认门）业务谓词——PRD 未产出（BA 未判定明确，门不 ready）。 */
    GATE_PRD_NOT_PRODUCED(409, "PRJ_016", "需求确认未通过：PRD 尚未产出");

    private final int httpStatus;
    private final String code;
    private final String message;

    ProjectMessage(int httpStatus, String code, String message) {
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
