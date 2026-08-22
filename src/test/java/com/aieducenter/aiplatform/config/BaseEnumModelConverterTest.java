package com.aieducenter.aiplatform.config;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.media.Schema;

import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.business.project.application.dto.command.CreateProjectCommand;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BaseEnum swagger 渲染不变式（#34）：全局 ModelConverter 让 schema 如实反映
 * 运行时序列化——BaseEnum 一律 {@code type=integer} + code→名称对照（swagger
 * 是唯一契约，ADR-0001），不再 string+name 枚举；非 BaseEnum 的 JDK enum 与
 * 普通字段不受影响。
 */
class BaseEnumModelConverterTest {

    private final ModelConverter converter = new SpringDocConfig().baseEnumModelConverter();

    @Test
    void given_base_enum_when_resolve_then_integer_with_code_table() {
        readWithConverter(() -> {
            Schema<?> schema = resolved(WaitKind.class);

            assertThat(schema).isNotNull();
            assertThat(schema.getType()).isEqualTo("integer");
            assertThat(schema.getEnum()).isNull(); // 不再渲染 string name 枚举
            assertThat(schema.getDescription()).contains("1=问答").contains("2=权限");
        });
    }

    @Test
    void given_record_with_enum_field_when_read_all_then_enum_integer_and_plain_field_untouched() {
        readWithConverter(() -> {
            Map<String, Schema> schemas =
                    ModelConverters.getInstance().readAll(CreateProjectCommand.class);
            Schema<?> typeProperty = (Schema<?>) schemas.get("CreateProjectCommand")
                    .getProperties().get("type");

            assertThat(typeProperty.getType()).isEqualTo("integer");
            assertThat(typeProperty.getDescription()).contains("1=官网");
            // 非 BaseEnum 字段不受影响：String 照旧 string
            Schema<?> name = (Schema<?>) schemas.get("CreateProjectCommand")
                    .getProperties().get("name");
            assertThat(name.getType()).isEqualTo("string");
        });
    }

    @Test
    void given_plain_jdk_enum_when_resolve_then_default_string_shape_untouched() {
        readWithConverter(() -> {
            Schema<?> schema = resolved(PlainJdkEnum.class);

            assertThat(schema.getType()).isEqualTo("string");
            assertThat(schema.<Object>getEnum())
                    .extracting(Object::toString)
                    .containsExactly("A", "B");
        });
    }

    /** converter 只接管 BaseEnum，注册/反注册在单例上收口（不污染其他测试）。 */
    private void readWithConverter(Runnable assertion) {
        ModelConverters converters = ModelConverters.getInstance();
        converters.addConverter(converter);
        try {
            assertion.run();
        } finally {
            converters.removeConverter(converter);
        }
    }

    private static Schema<?> resolved(Class<?> type) {
        ResolvedSchema resolved = ModelConverters.getInstance()
                .resolveAsResolvedSchema(new AnnotatedType().type(type));
        return resolved.schema;
    }

    /** 非 BaseEnum 的 JDK enum（反例：converter 不得越界接管）。 */
    enum PlainJdkEnum {
        A, B
    }
}
