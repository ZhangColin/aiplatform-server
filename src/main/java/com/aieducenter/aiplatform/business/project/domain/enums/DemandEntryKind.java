package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 需求池条目类型（A3 §4）：可空——收件时不强分类（「随手记一条」是常态）。
 * BUG 类型条目只是收件清单记录，不是缺陷实体（CONTEXT「Bug」词条 Avoid）。
 */
public enum DemandEntryKind implements BaseEnum<DemandEntryKind> {

    /** 需求。 */
    REQUIREMENT(1, "需求"),

    /** 缺陷反馈（入池是显式动作；修复过程走任务/Bug 系统，A4）。 */
    BUG(2, "缺陷");

    private final Integer code;
    private final String name;

    DemandEntryKind(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<DemandEntryKind> {
        public JpaConverter() {
            super(DemandEntryKind.class);
        }
    }
}
