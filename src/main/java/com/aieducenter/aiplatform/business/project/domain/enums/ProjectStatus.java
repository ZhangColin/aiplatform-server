package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 派生项目状态（A3 §1/§4，#34 收敛为 BaseEnum）：有无 OPEN 期的投影，非落库
 * 状态机——归档是真实动作（archived_at），优先于派生；交付 = 无 OPEN 期。
 * REST 以 Integer code 传递（BaseEnum 约定），展示名随附 statusName。
 */
public enum ProjectStatus implements BaseEnum<ProjectStatus> {

    IN_PROGRESS(1, "开发中"),

    DELIVERED(2, "已交付"),

    ARCHIVED(3, "已归档");

    private final Integer code;
    private final String name;

    ProjectStatus(Integer code, String name) {
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
