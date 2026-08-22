package com.aieducenter.aiplatform.business.task.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 任务状态（A4 §2 状态机）：PUBLISHED 已发布 → IN_PROGRESS 执行中 →
 * SUBMITTED 已提交 → CONFIRMED 已确认（终态）；CANCELLED 已取消（终态，
 * 仅已发布/执行中可进——已提交只能驳回）。非法迁移 409 TASK_。
 */
public enum TaskStatus implements BaseEnum<TaskStatus> {

    PUBLISHED(1, "已发布"),
    IN_PROGRESS(2, "执行中"),
    SUBMITTED(3, "已提交"),
    CONFIRMED(4, "已确认"),
    CANCELLED(5, "已取消");

    private final Integer code;
    private final String name;

    TaskStatus(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<TaskStatus> {
        public JpaConverter() {
            super(TaskStatus.class);
        }
    }
}
