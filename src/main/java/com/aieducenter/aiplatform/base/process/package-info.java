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
 *   <li>domain - 领域层：主链定义（StageEntry / ExitGate / MainChainDefinition，构造期
 *       校验 fail fast）、推进结果（AdvanceResult，门禁不足以值表达由持有方翻译 409
 *       PRJ_）、StageAdvanceService 阶段推进引擎（纯逻辑）</li>
 *   <li>application / infrastructure / endpoints - 无：无表无 REST，唯一调用方
 *       business.project 同进程直调引擎类（B0 蓝图 §2 片4）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Process", subDomain = SubDomain.SUPPORTING)
package com.aieducenter.aiplatform.base.process;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
