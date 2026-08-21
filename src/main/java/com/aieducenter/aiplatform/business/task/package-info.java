/**
 * Task Context（business.task）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>任务系统：测试外包循环（HITL 等待点转任务，执行方 = 自己 / 内部指派 / 外包 OPC）</li>
 *   <li>Bug 三态（待修复 → 已修复 → 复测通过；复测通过是唯一关闭态）与修复编排链</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>任务与项目过程正交（挂项目常开，不锁期）；修复编排经 business.project
 * 工作区任务下发端口复用片5 编排。表前缀 {@code tsk_}，错误码前缀 {@code TASK_}。A4 建。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>四层骨架（domain / application / infrastructure / endpoints）随 A4 落位</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Task", subDomain = SubDomain.CORE)
package com.aieducenter.aiplatform.business.task;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
