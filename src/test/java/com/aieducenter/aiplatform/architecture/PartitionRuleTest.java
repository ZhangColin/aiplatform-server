package com.aieducenter.aiplatform.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.ArchFixtureBaseClean;
import com.aieducenter.aiplatform.base.ArchFixtureBaseViolation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分区规则「违例被拦」验证：用 fixture 单类导入证明规则真的会拒绝
 * base → business 的依赖，而不是空转。
 */
class PartitionRuleTest {

    @Test
    void given_base_class_imports_business_when_check_partition_rule_then_violation_is_rejected() {
        JavaClasses classes = new ClassFileImporter().importClasses(ArchFixtureBaseViolation.class);

        assertThatThrownBy(() -> PartitionRules.BASE_MUST_NOT_DEPEND_ON_BUSINESS.check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ArchFixtureBaseViolation")
                .hasMessageContaining("base 分区零业务概念");
    }

    @Test
    void given_base_class_without_business_dependency_when_check_partition_rule_then_passes() {
        JavaClasses classes = new ClassFileImporter().importClasses(ArchFixtureBaseClean.class);

        assertThatCode(() -> PartitionRules.BASE_MUST_NOT_DEPEND_ON_BUSINESS.check(classes))
                .doesNotThrowAnyException();
    }
}
