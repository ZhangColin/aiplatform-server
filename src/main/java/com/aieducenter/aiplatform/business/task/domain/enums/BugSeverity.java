package com.aieducenter.aiplatform.business.task.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * Bug 严重档位（A4 §1「枚举档位实现票定名」——本票定名）：四档 BLOCKER 致命
 * （阻断主流程）/ CRITICAL 严重（核心功能受损）/ MAJOR 一般（主要功能异常）/
 * MINOR 轻微（边界与体验）。前端下拉与提交载荷均以此 Integer code 交互。
 */
public enum BugSeverity implements BaseEnum<BugSeverity> {

    BLOCKER(1, "致命"),
    CRITICAL(2, "严重"),
    MAJOR(3, "一般"),
    MINOR(4, "轻微");

    private final Integer code;
    private final String name;

    BugSeverity(Integer code, String name) {
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

    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<BugSeverity> {
        public JpaConverter() {
            super(BugSeverity.class);
        }
    }
}
