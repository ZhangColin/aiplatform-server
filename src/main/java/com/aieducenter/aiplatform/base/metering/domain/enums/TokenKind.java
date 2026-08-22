package com.aieducenter.aiplatform.base.metering.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * token 档位（A6 §1 单价表五档枚举，与 domain.model.TokenUsage 同词表）：互斥分解
 * 的五档各配各的单价，平台成本 = Σ(档位用量 × 事件时点生效单价) 不重复计费。
 *
 * <p>落库与契约均为 Integer code（#34 收敛房规）；spec §1 草案的小写字符串词表
 * （input/output/...）对应本枚举常量名。</p>
 */
public enum TokenKind implements BaseEnum<TokenKind> {

    INPUT(1, "输入"),
    OUTPUT(2, "输出"),
    CACHE_READ(3, "缓存读"),
    CACHE_WRITE(4, "缓存写"),
    REASONING(5, "推理");

    private final Integer code;
    private final String name;

    TokenKind(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<TokenKind> {
        public JpaConverter() {
            super(TokenKind.class);
        }
    }
}
