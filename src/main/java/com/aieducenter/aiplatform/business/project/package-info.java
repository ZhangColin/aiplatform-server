/**
 * Project Context（business.project）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>项目聚合（业务字段 + workspaceId + owner）与主链总装（对话建项目 + 智能体编排）</li>
 *   <li>期聚合（状态机主体在期，A3 §2.1）；确认留痕 / 需求池实体归片5b/5c（票 #23/#24）</li>
 *   <li>角色卡 preset（六角色，代码配置不落库）与主链定义（唯一一条，传 base.process）</li>
 *   <li>SSE 编排层发射：平台通知（workspace-created / stage-changed / workspace-destroyed）
 *       + agent 流 projectId 桥接与 role-assigned / wait-settled 发射</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>交付业务核心：编排 base 各端口（环境 / 智能体 / 流程）走通主链；平台通知 SSE
 * 由本上下文编排层在副作用落定后发射。表前缀 {@code prj_}，错误码前缀 {@code PRJ_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：聚合根（Project、Iteration）、角色卡 preset 与主链定义（model）、仓储接口、枚举、错误码</li>
 *   <li>application - 应用层：应用服务（Lifecycle / AgentTask / Wait）、SSE 事件名册常量、DTO</li>
 *   <li>infrastructure - 基础设施层：base.chatagent 的 PRD 产物端口实现（savePrd 效果
 *       半边：置状态位 + document-updated，#49）</li>
 *   <li>endpoints - 北向接口适配器层：REST API（ProjectController、ProjectAgentController）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Project", subDomain = SubDomain.CORE)
package com.aieducenter.aiplatform.business.project;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
