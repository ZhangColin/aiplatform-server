/**
 * Metering Context（base.metering）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>用量采集（UsageEventSink 端口，token 五档）与存储（met_usage_events）</li>
 *   <li>按 subject 聚合查询（UsageQueryPort）与平台成本换算（met_price_entries 单价表，A6）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>零商业概念：存储只记 token、换算只出平台成本（币种分桶）、无加价/售价/账单；
 * 平台内起步，独立计量服务是演化方向（四段整体迁，换上报 / 查询适配器）。
 * 计量 ≠ 路由：模型选择 / 档位路由归 base.agentengine。表前缀 {@code met_}，
 * 错误码前缀 {@code METER_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：UsageEvent 协议与 TokenUsage 五档（三家归一化）、UsageSummary
 *       读模型、UsageEventEntry 聚合（met_usage_events）、UsageEventSink / UsageQueryPort
 *       端口（CLIENT 直调）、仓储 + 读侧聚合 fragment</li>
 *   <li>application - 应用层：MeteringAppService（幂等上报 + bySubject 聚合）</li>
 *   <li>infrastructure - 基础设施层：MeteringLocalAdapter（端口进程内适配，迁出换 REST）、
 *       persistence（jsonb 维度聚合的原生 SQL fragment 实现）</li>
 *   <li>endpoints - 无 REST 面：消费端点随片5c（usage 基础版）与 A6（cost 扩展）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Metering", subDomain = SubDomain.SUPPORTING)
package com.aieducenter.aiplatform.base.metering;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
