package com.aieducenter.aiplatform.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cartisan.core.domain.BaseEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * swagger 枚举契约（#34 验收）：起全上下文拉 {@code /v3/api-docs/{group}}，
 * 全部分组中不得有任何 BaseEnum 渲染成 string+name 枚举（运行时 JSON 双向是
 * Integer code，swagger 是唯一契约，ADR-0001）；另抽代表性分组断言枚举字段
 * {@code type=integer}，防「无枚举出现」的空转通过。BaseEnum 清单经类路径
 * 扫描收集——新增 BC 枚举自动纳入，不随漂移。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpringDocEnumContractTest {

    private static final List<String> GROUPS = List.of(
            "workspace", "agentengine", "eventhub", "knowledge", "metering",
            "project", "identity", "workbench", "task");

    private static final String BASE_PACKAGE = "com.aieducenter.aiplatform";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void given_all_groups_when_read_api_docs_then_no_base_enum_renders_as_string() throws Exception {
        Set<Class<?>> enumClasses = baseEnumClasses();
        assertThat(enumClasses).isNotEmpty(); // 扫描有效（含 WaitKind 等）
        Set<Set<String>> baseEnumNameSets = toNameSets(enumClasses);
        Set<String> codeTables = toCodeTables(enumClasses);

        for (String group : GROUPS) {
            JsonNode doc = fetchGroup(group);
            List<String> violations = new java.util.ArrayList<>();
            collectStringEnumViolations(doc, baseEnumNameSets, "$", violations);
            assertThat(violations)
                    .as("分组 %s 中 BaseEnum 不得渲染为 string+name 枚举", group)
                    .isEmpty();
            // 正向半边：凡携带 converter 生成 code 表的 schema 必须 type=integer
            List<String> nonInteger = new java.util.ArrayList<>();
            collectCodeTableNodesNotInteger(doc, codeTables, "$", nonInteger);
            assertThat(nonInteger)
                    .as("分组 %s 中携带 code 表的 BaseEnum schema 必须 type=integer", group)
                    .isEmpty();
        }
    }

    @Test
    void given_representative_groups_when_read_schemas_then_enum_fields_are_integer() throws Exception {
        // project：ProjectResponse.status（#34 由 String 投影收敛为 BaseEnum）
        JsonNode project = fetchGroup("project");
        assertThat(enumFieldType(project, "ProjectResponse", "status")).isEqualTo("integer");
        assertThat(enumFieldType(project, "ProjectAgentTaskResponse", "role")).isEqualTo("integer");
        // workspace / task：既有枚举字段同样 integer
        assertThat(enumFieldType(fetchGroup("workspace"), "CreateWorkspaceCommand", "kind"))
                .isEqualTo("integer");
        assertThat(enumFieldType(fetchGroup("task"), "TaskResponse", "status"))
                .isEqualTo("integer");
    }

    // ---------- 装载与遍历 ----------

    private JsonNode fetchGroup(String group) throws Exception {
        MockHttpServletRequestBuilder request = get("/v3/api-docs/" + group);
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** 深度遍历：任何 string 型 enum 值集与某 BaseEnum 名集相同即违例。 */
    private void collectStringEnumViolations(JsonNode node, Set<Set<String>> baseEnumNameSets,
                                             String path, List<String> violations) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode type = node.path("type");
            JsonNode values = node.path("enum");
            if ("string".equals(type.asText()) && values.isArray()) {
                Set<String> names = new HashSet<>();
                values.forEach(value -> names.add(value.asText()));
                if (baseEnumNameSets.contains(names)) {
                    violations.add(path);
                }
            }
            node.fields().forEachRemaining(entry ->
                    collectStringEnumViolations(entry.getValue(), baseEnumNameSets,
                            path + "." + entry.getKey(), violations));
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectStringEnumViolations(node.get(i), baseEnumNameSets,
                        path + "[" + i + "]", violations);
            }
        }
    }

    /** 正向半边：携带 code 表描述的节点（即 BaseEnum 渲染处）必须 type=integer。 */
    private void collectCodeTableNodesNotInteger(JsonNode node, Set<String> codeTables,
                                                 String path, List<String> violations) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode description = node.path("description");
            if (!description.isMissingNode() && codeTables.contains(description.asText())
                    && !"integer".equals(node.path("type").asText())) {
                violations.add(path);
            }
            node.fields().forEachRemaining(entry ->
                    collectCodeTableNodesNotInteger(entry.getValue(), codeTables,
                            path + "." + entry.getKey(), violations));
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectCodeTableNodesNotInteger(node.get(i), codeTables,
                        path + "[" + i + "]", violations);
            }
        }
    }

    /** components.schemas.{schema}.properties.{field}.type（内联 integer 或 $ref 均可辨）。 */
    private String enumFieldType(JsonNode doc, String schema, String field) {
        JsonNode property = doc.path("components").path("schemas").path(schema)
                .path("properties").path(field);
        assertThat(property.isMissingNode())
                .as("分组应含 %s.%s（缺 schema 视为契约漂移）", schema, field)
                .isFalse();
        return property.path("type").asText(null);
    }

    /** 类路径扫描全部 BaseEnum 枚举类（新增 BC 自动纳入）。 */
    private static Set<Class<?>> baseEnumClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BaseEnum.class));
        return scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .map(candidate -> {
                    try {
                        return Class.forName(candidate.getBeanClassName(), false,
                                SpringDocEnumContractTest.class.getClassLoader());
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .filter(Class::isEnum)
                .collect(Collectors.toSet());
    }

    private static Set<Set<String>> toNameSets(Set<Class<?>> enumClasses) {
        return enumClasses.stream()
                .map(clazz -> java.util.Arrays.stream(clazz.getEnumConstants())
                        .map(value -> ((Enum<?>) value).name())
                        .collect(Collectors.toSet()))
                .collect(Collectors.toSet());
    }

    /** converter 生成的 code 表串（"1=问答, 2=权限" 形，与 SpringDocConfig 同式）。 */
    private static Set<String> toCodeTables(Set<Class<?>> enumClasses) {
        return enumClasses.stream()
                .map(clazz -> java.util.Arrays.stream(clazz.getEnumConstants())
                        .map(BaseEnum.class::cast)
                        .map(value -> value.getCode() + "=" + value.getName())
                        .collect(Collectors.joining(", ")))
                .collect(Collectors.toSet());
    }
}
