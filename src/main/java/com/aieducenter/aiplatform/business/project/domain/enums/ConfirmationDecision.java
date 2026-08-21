package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 确认决策（A3 §3）：通过 = 推进（末门即收口）；驳回 = 一律停留当前阶段
 * （reason 必填）。approve 也留痕（交付审计 + A5 纪要素材）。
 */
public enum ConfirmationDecision implements BaseEnum<ConfirmationDecision> {

    APPROVED(1, "通过"),
    REJECTED(2, "驳回");

    private final Integer code;
    private final String name;

    ConfirmationDecision(Integer code, String name) {
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
     * JPA Converter（框架自动应用，实体字段无需 @Convert）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<ConfirmationDecision> {
        public JpaConverter() {
            super(ConfirmationDecision.class);
        }
    }
}
