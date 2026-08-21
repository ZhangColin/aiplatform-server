package com.aieducenter.aiplatform.base;

import com.aieducenter.aiplatform.business.ArchFixtureBusinessComponent;

/**
 * 仅供 ArchUnit 分区规则违例构造（PartitionRuleTest）：base → import business 的
 * 反例，勿在别处引用。测试类不进 @AnalyzeClasses 产物（DoNotIncludeTests），
 * 只被 PartitionRuleTest 用 ClassFileImporter 单类导入。
 */
public class ArchFixtureBaseViolation {

    private final ArchFixtureBusinessComponent businessComponent = new ArchFixtureBusinessComponent();
}
