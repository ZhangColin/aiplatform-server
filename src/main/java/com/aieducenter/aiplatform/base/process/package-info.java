/**
 * Process Context（base.process）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>阶段推进引擎：主链定义解析 / next() 迁移 / 驳回停留 / 门禁计数（minTasks 按阶段可配）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>无表——阶段序列由业务侧传入（平台主链定义，A3：「模板」概念退役），状态由
 * 持有方（business.project 的期聚合）保存；引擎不知业务内容。无表、无错误码前缀。
 * 与 agentengine 的边界：决策门（流程层）与 HITL 等待点（智能体层）不统一建模。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>四层骨架（domain / application / infrastructure / endpoints）随片4 落位</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Process", subDomain = SubDomain.SUPPORTING)
package com.aieducenter.aiplatform.base.process;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
