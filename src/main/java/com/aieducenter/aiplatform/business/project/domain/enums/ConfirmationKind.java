package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 确认种类（A3 §3，四扇门一一对应）：kind 存的是「哪一扇门」的决策，与阶段名
 * 是两套键（测试段的门叫「开发完成确认」）。阶段 → 种类的对应归主链定义
 * （{@code ProjectMainChain.confirmationKindOf}，与出口门定义同处一文件）。
 */
public enum ConfirmationKind implements BaseEnum<ConfirmationKind> {

    /** 需求确认（G1，需求梳理段出口·用户）。 */
    REQUIREMENT(1, "需求确认"),

    /** Demo 确认（G2，Demo 段出口·用户）。 */
    DEMO(2, "Demo确认"),

    /** 开发完成确认（G3，测试段出口·开发平台；业务谓词 = 无未关闭 Bug，A3 §2.4）。 */
    DEVELOPMENT(3, "开发完成确认"),

    /** 验收（G4，验收段出口·用户；通过即收口）。 */
    ACCEPTANCE(4, "验收");

    private final Integer code;
    private final String name;

    ConfirmationKind(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<ConfirmationKind> {
        public JpaConverter() {
            super(ConfirmationKind.class);
        }
    }
}
