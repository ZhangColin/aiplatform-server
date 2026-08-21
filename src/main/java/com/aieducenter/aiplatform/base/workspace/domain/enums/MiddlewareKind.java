package com.aieducenter.aiplatform.base.workspace.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 中间件资源种类（CONTEXT.md「中间件资源」）：随环境生命周期供给与隔离。
 * Phase A 供给 PostgreSQL 与 Redis；对象存储等随需要扩枚举。
 */
public enum MiddlewareKind implements BaseEnum<MiddlewareKind> {

    POSTGRESQL(1, "PostgreSQL"),
    REDIS(2, "Redis");

    private final Integer code;
    private final String name;

    MiddlewareKind(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<MiddlewareKind> {
        public JpaConverter() {
            super(MiddlewareKind.class);
        }
    }
}
