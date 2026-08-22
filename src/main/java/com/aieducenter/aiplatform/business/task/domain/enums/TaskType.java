package com.aieducenter.aiplatform.business.task.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 任务类型（A4 §1）：v1 单值 TEST。**确认模式不落列**——由 type 推导
 * （类型定义 = 代码概念：TEST → 人工确认）；自动确认类型是将来加 type 时的
 * 映射扩展（{@link #requiresManualConfirmation()}）。
 */
public enum TaskType implements BaseEnum<TaskType> {

    TEST(1, "测试任务");

    private final Integer code;
    private final String name;

    TaskType(Integer code, String name) {
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

    /** 确认模式推导（A4 §0 已继承决策）：v1 唯一类型 TEST = 人工确认。 */
    public boolean requiresManualConfirmation() {
        return this == TEST;
    }

    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<TaskType> {
        public JpaConverter() {
            super(TaskType.class);
        }
    }
}
