package com.aieducenter.aiplatform.config;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.type.TypeFactory;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cartisan.core.domain.BaseEnum;

/**
 * SpringDoc 全局配置（片0，ADR-0001）。
 *
 * <p>swagger 是前后端唯一契约（本机 http://localhost:8888/swagger-ui/index），
 * 不维护任何 REST 对接文档。书写约定：</p>
 * <ul>
 *   <li>分组按 BC（packagesToScan 扫各 BC 包，本类统一注册）</li>
 *   <li>tag / summary 中文；路径 / 字段 / schema 名英文</li>
 *   <li>路径 /api/*，无版本段；全端点 ApiResponse&lt;T&gt; / PageResponse&lt;T&gt;</li>
 * </ul>
 * <p>SSE 是非 REST 通道，事件名册见 docs/spec/SSE事件清单.md，不进 swagger。</p>
 */
@Configuration
public class SpringDocConfig {

    private static final String BASE_PACKAGE = "com.aieducenter.aiplatform.";

    @Bean
    public OpenAPI aiplatformOpenApi() {
        return new OpenAPI().info(new Info()
                .title("AI 开发平台后端 API")
                .description("swagger 即唯一契约（ADR-0001）：成功判定 = HTTP 2xx，"
                        + "分页 1 基（默认 size=20），错误码前缀见各 BC 注册（WSP_/AGT_/KNW_/PRJ_/…）。"
                        + "SSE 双通道事件名册：docs/spec/SSE事件清单.md。")
                .version("1.0.0-SNAPSHOT"));
    }

    // -------- base 分区 --------

    @Bean
    public GroupedOpenApi workspaceGroup() {
        return bcGroup("workspace", "工作区与环境（base.workspace）", "base.workspace");
    }

    @Bean
    public GroupedOpenApi agentEngineGroup() {
        return bcGroup("agentengine", "智能体引擎（base.agentengine）", "base.agentengine");
    }

    @Bean
    public GroupedOpenApi eventHubGroup() {
        return bcGroup("eventhub", "事件中心 SSE（base.eventhub）", "base.eventhub");
    }

    @Bean
    public GroupedOpenApi knowledgeGroup() {
        return bcGroup("knowledge", "知识库（base.knowledge）", "base.knowledge");
    }

    @Bean
    public GroupedOpenApi meteringGroup() {
        return bcGroup("metering", "计量（base.metering）", "base.metering");
    }

    // -------- business 分区（process 无 REST 面，不设组）--------

    @Bean
    public GroupedOpenApi projectGroup() {
        return bcGroup("project", "项目主链（business.project）", "business.project");
    }

    @Bean
    public GroupedOpenApi identityGroup() {
        return bcGroup("identity", "账号认证（business.identity）", "business.identity");
    }

    @Bean
    public GroupedOpenApi workbenchGroup() {
        return bcGroup("workbench", "工作台（business.workbench）", "business.workbench");
    }

    @Bean
    public GroupedOpenApi taskGroup() {
        return bcGroup("task", "任务系统（business.task）", "business.task");
    }

    private GroupedOpenApi bcGroup(String group, String displayName, String bcPackage) {
        return GroupedOpenApi.builder()
                .group(group)
                .displayName(displayName)
                .packagesToScan(BASE_PACKAGE + bcPackage)
                .build();
    }

    // -------- BaseEnum schema 渲染（#34） --------

    /**
     * BaseEnum 全局 schema 定制（#34）：springdoc 不感知 cartisan-web 的
     * Jackson {@code serializerByType} 注册，会把 BaseEnum 渲染成 string+name
     * 枚举，与运行时「JSON 双向 Integer code」冲突。此处一处注册全局生效
     * （springdoc 的 ModelConverterRegistrar 收集容器里全部 {@link ModelConverter}
     * bean，各 BC 分组自动带上）：BaseEnum → {@code type=integer} + code→名称
     * 对照描述；其余类型原样交给链条后续 converter。禁止逐字段 {@code @Schema}
     * 硬编码——会随新 BC 漂移。
     */
    @Bean
    public ModelConverter baseEnumModelConverter() {
        return (type, context, chain) -> {
            Schema<?> integerSchema = baseEnumSchemaOf(type);
            if (integerSchema != null) {
                return integerSchema;
            }
            return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        };
    }

    /** BaseEnum 类型 → integer schema（含 code→名称对照）；非 BaseEnum 返回 null。 */
    private static Schema<?> baseEnumSchemaOf(AnnotatedType type) {
        if (type == null || type.getType() == null) {
            return null;
        }
        Class<?> rawClass = rawClassOf(type.getType());
        if (rawClass == null || !BaseEnum.class.isAssignableFrom(rawClass)) {
            return null;
        }
        String codeTable = Arrays.stream(rawClass.getEnumConstants())
                .map(BaseEnum.class::cast)
                .map(value -> value.getCode() + "=" + value.getName())
                .collect(Collectors.joining(", "));
        return new Schema<>().type("integer").description(codeTable);
    }

    /** 提取裸类：属性解析路径上 Type 可能是 Class，也可能是 Jackson 解析形。 */
    private static Class<?> rawClassOf(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        try {
            return TypeFactory.defaultInstance().constructType(type).getRawClass();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
