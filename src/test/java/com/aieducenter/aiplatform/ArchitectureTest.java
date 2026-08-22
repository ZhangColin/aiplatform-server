package com.aieducenter.aiplatform;

import com.cartisan.test.archunit.CartisanArchRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import com.aieducenter.aiplatform.architecture.PartitionRules;

/**
 * 架构守护测试：cartisan 全量规则（分层 / 命名 / 禁止 / 编码规范）+ 本项目分区规则。
 * 规范正本：docs/guide/限界上下文代码编写规范.md §10；分区规则见 B0 蓝图 §1。
 */
@AnalyzeClasses(packages = "com.aieducenter.aiplatform", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest extends CartisanArchRules {

    @ArchTest
    static final ArchRule base_should_not_depend_on_business = PartitionRules.BASE_MUST_NOT_DEPEND_ON_BUSINESS;

    @ArchTest
    static final ArchRule workbench_should_be_query_side_only = PartitionRules.WORKBENCH_MUST_BE_QUERY_SIDE_ONLY;
}
