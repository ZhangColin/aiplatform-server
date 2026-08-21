/**
 * Project Context（business.project）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>项目聚合（业务字段 + workspaceId + owner）与主链 Controller 总装</li>
 *   <li>期 / 确认留痕 / 需求池实体（状态机主体在期，A3）</li>
 *   <li>角色卡 preset（六角色，代码配置不落库）与阶段→产物文件名映射</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>交付业务核心：编排 base 各端口（环境 / 智能体 / 知识 / 流程）走通主链；
 * 平台通知 SSE 由本上下文编排层在副作用落定后发射。表前缀 {@code prj_}，
 * 错误码前缀 {@code PRJ_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>四层骨架（domain / application / infrastructure / endpoints）随片5 落位</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Project", subDomain = SubDomain.CORE)
package com.aieducenter.aiplatform.business.project;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
