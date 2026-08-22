package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 项目列表状态过滤桶（A2 §63 + A3 §4，#34 收敛为 BaseEnum）：跨枚举的派生
 * 谓词，非 {@link ProjectStatus} 的直接值域——pending = 未归档 ∧ 存在 dev
 * 待办（期门就绪 ∨ 工作区待处理等待点），无对应落库状态。REST query param
 * 以 Integer code 传递（框架 converter 按 code 绑定），不合法取值 400 PRJ_014。
 */
public enum ProjectStatusFilter implements BaseEnum<ProjectStatusFilter> {

    ACTIVE(1, "开发中"),

    PENDING(2, "存在待办"),

    ARCHIVED(3, "已归档");

    private final Integer code;
    private final String name;

    ProjectStatusFilter(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }
}
