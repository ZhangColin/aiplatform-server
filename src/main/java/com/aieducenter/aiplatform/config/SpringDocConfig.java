package com.aieducenter.aiplatform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    // BaseEnum schema 全局渲染（BaseEnum → integer + code→名称对照，#34）已上提
    // cartisan-web（SpringDocIntegrationConfiguration，cartisan-boot#20）——本地
    // 同名 bean 删除避免 BeanDefinitionOverride（新 SNAPSHOT 起由框架独占提供）。
}
