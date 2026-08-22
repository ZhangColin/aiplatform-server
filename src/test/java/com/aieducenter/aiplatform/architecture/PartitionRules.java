package com.aieducenter.aiplatform.architecture;

import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 分区守护规则（B0 蓝图 §1：单 module 双分区，依赖方向靠 ArchUnit 守护）。
 *
 * <p>规则定义与违例构造测试（{@code PartitionRuleTest}）共用同一份 {@link ArchRule}，
 * 保证「被拦」验证的就是线上生效的那条规则。</p>
 */
public final class PartitionRules {

    /**
     * base 分区不得依赖 business 分区：base 是零业务概念的基础设施能力层，
     * 只能被 business 消费，不得反向依赖（违例构造见 test 源的 ArchFixture*）。
     */
    public static final ArchRule BASE_MUST_NOT_DEPEND_ON_BUSINESS = noClasses()
            .that()
            .resideInAPackage("..aiplatform.base..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..aiplatform.business..")
            .because("base 分区零业务概念，只经端口被 business 消费（B0 蓝图 §1 / CONTEXT.md「底座」）");

    /**
     * workbench 是纯查询侧聚合（A2 §5）：无表、无 repository、无领域实体——
     * 待办是计算式投影，不得沉淀 domain / infrastructure 形态（违例构造见
     * test 源的 ArchFixtureWorkbench*）。
     */
    public static final ArchRule WORKBENCH_MUST_BE_QUERY_SIDE_ONLY = noClasses()
            .that()
            .resideInAPackage("..aiplatform.business.workbench..")
            .should()
            .resideInAnyPackage("..aiplatform.business.workbench.domain..",
                    "..aiplatform.business.workbench.infrastructure..")
            .because("workbench 是查询侧聚合（无表无领域实体，A2 §5）——投影不得变实体");

    private PartitionRules() {
    }
}
