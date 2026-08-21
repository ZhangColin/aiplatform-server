package com.aieducenter.aiplatform.base.workspace.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 环境生命周期（CONTEXT.md「环境」）：dev = agent 工作区（写码/打包/运行）；
 * test/prod = 纯运行打包产物（门槛高，不开放 exec/写文件）。
 */
public enum EnvKind implements BaseEnum<EnvKind> {

    DEV(1, "开发"),
    TEST(2, "测试"),
    PROD(3, "生产");

    private final Integer code;
    private final String name;

    EnvKind(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<EnvKind> {
        public JpaConverter() {
            super(EnvKind.class);
        }
    }
}
