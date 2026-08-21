package com.aieducenter.aiplatform.base.agentengine.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 等待点生命周期（CONTEXT.md「等待点」）：PENDING → SETTLED（人已答复：
 * settle_outcome 记具体结果）/ EXPIRED（run 终态联动：finish/error/timeout/cancel）/
 * CANCELLED（复用会话下发前的残留清理）。迁移只能从 PENDING 出发，单向不可逆。
 */
public enum WaitStatus implements BaseEnum<WaitStatus> {

    PENDING(1, "待处理"),

    SETTLED(2, "已答复"),

    EXPIRED(3, "已失效"),

    CANCELLED(4, "已清理");

    private final Integer code;
    private final String name;

    WaitStatus(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<WaitStatus> {

        public JpaConverter() {
            super(WaitStatus.class);
        }
    }
}
