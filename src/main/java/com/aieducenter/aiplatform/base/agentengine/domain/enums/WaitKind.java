package com.aieducenter.aiplatform.base.agentengine.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 等待点种类（A1 §1.1 统一模型）：问答（agent 向用户提问，引擎 que_* 机制）与
 * 权限（agent 请求工具权限，人批准/拒绝）——两类挂起统一由等待点承载，不分开建模。
 */
public enum WaitKind implements BaseEnum<WaitKind> {

    QUESTION(1, "问答"),

    PERMISSION(2, "权限");

    private final Integer code;
    private final String name;

    WaitKind(Integer code, String name) {
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

    /**
     * JPA Converter（autoApply，实体字段零注解）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<WaitKind> {

        public JpaConverter() {
            super(WaitKind.class);
        }
    }
}
