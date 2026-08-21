/**
 * base 分区：基础设施能力层（端口-适配器），零业务概念。
 *
 * <p>只经端口被 business 分区消费，同进程调用起步，将来拆服务换端口实现。
 * 分区不是限界上下文——BC 是区内每个包（workspace / agentengine / eventhub /
 * knowledge / process / metering），各带自己的 package-info 与 {@code @BoundedContext}。</p>
 *
 * <p>架构守护：本分区不得 import business 分区（ArchUnit 规则，见
 * {@code com.aieducenter.aiplatform.ArchitectureTest}）；词汇见 CONTEXT.md「底座」。</p>
 */
package com.aieducenter.aiplatform.base;
