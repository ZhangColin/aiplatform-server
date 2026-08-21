/**
 * AgentEngine Context（base.agentengine）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>开发智能体适配层（CodingAgentAdapter 端口：runTask / pendingQuestions /
 *       replyQuestions / replyPermission / health；systemPrompt 与 modelId 是入参）</li>
 *   <li>引擎注册表（OpenCode / Dsh，显式注册）+ 模型档位配置</li>
 *   <li>agent 会话落库（按 workspaceId 寻址）与等待点（agt_pending_waits）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>底座智能体接入能力，抹平引擎差异、不含角色概念（角色卡在 business.project）。
 * 表前缀 {@code agt_}，错误码前缀 {@code AGT_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>四层骨架（domain / application / infrastructure / endpoints）随片2 落位</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "AgentEngine", subDomain = SubDomain.GENERIC)
package com.aieducenter.aiplatform.base.agentengine;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
