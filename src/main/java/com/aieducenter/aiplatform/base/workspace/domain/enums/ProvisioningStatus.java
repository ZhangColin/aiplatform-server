package com.aieducenter.aiplatform.base.workspace.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 工作区置备状态（CONTEXT.md「置备状态」）：环境的生命周期状态。
 * provisioning（置备中）→ ready（就绪）/ failed（失败）。对话与环境就绪解耦——
 * 状态只用于管理环境可用性，不暴露给对话 UI；失败在工作台可见、需要环境的能力时阻塞。
 */
public enum ProvisioningStatus implements BaseEnum<ProvisioningStatus> {

    PROVISIONING(1, "置备中"),
    READY(2, "就绪"),
    FAILED(3, "失败");

    private final Integer code;
    private final String name;

    ProvisioningStatus(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<ProvisioningStatus> {
        public JpaConverter() {
            super(ProvisioningStatus.class);
        }
    }
}
