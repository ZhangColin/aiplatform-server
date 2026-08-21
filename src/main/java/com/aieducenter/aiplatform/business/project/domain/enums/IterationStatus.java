package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 期状态（A3 §2.1）：OPEN = 过程进行中（每项目至多一个）；CLOSED = 收口
 * （验收门通过联动关期）。项目「开发中/已交付」= 有无 OPEN 期的派生投影。
 */
public enum IterationStatus implements BaseEnum<IterationStatus> {

    OPEN(1, "进行中"),
    CLOSED(2, "已收口");

    private final Integer code;
    private final String name;

    IterationStatus(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<IterationStatus> {
        public JpaConverter() {
            super(IterationStatus.class);
        }
    }
}
