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
 *   <li>四层骨架（domain / application / infrastructure / endpoints）随 A1/A6 切片落位</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Metering", subDomain = SubDomain.SUPPORTING)
package com.aieducenter.aiplatform.base.metering;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
