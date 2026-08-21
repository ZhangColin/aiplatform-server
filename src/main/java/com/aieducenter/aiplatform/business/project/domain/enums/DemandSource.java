package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 需求池条目来源（A3 §4）：谁记下的——用户 / 测试 / 验收，开新期时是需求梳理
 * 的输入（纪要回溯的线索）。来源是记录事实，不是权限（验收前后都能记、
 * 用户/测试都能提）。
 */
public enum DemandSource implements BaseEnum<DemandSource> {

    /** 用户（需求端随手记；缺省来源）。 */
    USER(1, "用户"),

    /** 测试（测试过程发现的缺陷/改进点）。 */
    TEST(2, "测试"),

    /** 验收（验收反馈中显式入池的条目——驳回反馈不自动入池，A3 §4）。 */
    ACCEPTANCE(3, "验收");

    private final Integer code;
    private final String name;

    DemandSource(Integer code, String name) {
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

    /** 缺省来源：用户（收件不指定来源时）。 */
    public static DemandSource orDefault(DemandSource source) {
        return source == null ? USER : source;
    }

    /**
     * JPA Converter（框架自动应用，实体字段无需 @Convert）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<DemandSource> {
        public JpaConverter() {
            super(DemandSource.class);
        }
    }
}
