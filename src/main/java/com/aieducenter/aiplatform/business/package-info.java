/**
 * business 分区：交付业务（CORE 子域），按 DDD 设计（限界上下文 / 聚合 / 应用事件 / 统一语言）。
 *
 * <p>区内每个包是一个限界上下文（project / identity / workbench / task，远期
 * billing / asset / processconfig），各带自己的 package-info 与 {@code @BoundedContext}；
 * 只经端口消费 base 分区能力。词汇见 CONTEXT.md「业务层」。</p>
 */
package com.aieducenter.aiplatform.business;
