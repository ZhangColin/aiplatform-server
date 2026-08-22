package com.aieducenter.aiplatform.business.task.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * Bug 三态（A4 §1，CONTEXT.md「Bug」）：OPEN 待修复 / FIXED 已修复（修复链
 * 乐观翻转，#27）/ VERIFIED 复测通过——**唯一关闭态**（手工关闭是其带理由的
 * 别名动作，不加第四态）。G3 谓词 open = status ≠ VERIFIED。
 */
public enum BugStatus implements BaseEnum<BugStatus> {

    OPEN(1, "待修复"),
    FIXED(2, "已修复"),
    VERIFIED(3, "复测通过");

    private final Integer code;
    private final String name;

    BugStatus(Integer code, String name) {
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

    /** 未关闭（G3 业务谓词口径，A4 §5）：非 VERIFIED 即未关闭——含已修复待复测。 */
    public boolean open() {
        return this != VERIFIED;
    }

    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<BugStatus> {
        public JpaConverter() {
            super(BugStatus.class);
        }
    }
}
