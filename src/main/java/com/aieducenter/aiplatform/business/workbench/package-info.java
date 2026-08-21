/**
 * Workbench Context（business.workbench）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>门户读模型查询聚合：把散在各上下文的状态拼成待办列表等门户视图</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>查询侧聚合，无表、无领域实体、无错误码前缀——待办是各处已有状态的实时
 * 计算式投影（等待答复 / 门待拍板 / 任务待确认 / 可发复测 / 新任务 / 被驳回），
 * 非独立落库实体。A2 建。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>四层骨架（domain / application / infrastructure / endpoints）随 A2 落位</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Workbench", subDomain = SubDomain.SUPPORTING)
package com.aieducenter.aiplatform.business.workbench;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
