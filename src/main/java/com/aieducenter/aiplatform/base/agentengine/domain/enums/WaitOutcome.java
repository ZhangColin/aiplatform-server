package com.aieducenter.aiplatform.base.agentengine.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * settle 的具体结果（仅 status=SETTLED 时落 settle_outcome；EXPIRED/CANCELLED 无
 * 结果）。SSE wait-settled 的 outcome 字段同名小写（SSE事件清单·通道二）。
 */
public enum WaitOutcome implements BaseEnum<WaitOutcome> {

    ANSWERED(1, "已回答"),

    APPROVED(2, "已批准"),

    DENIED(3, "已拒绝"),

    DEFERRED(4, "已转任务");

    private final Integer code;
    private final String name;

    WaitOutcome(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<WaitOutcome> {

        public JpaConverter() {
            super(WaitOutcome.class);
        }
    }
}
