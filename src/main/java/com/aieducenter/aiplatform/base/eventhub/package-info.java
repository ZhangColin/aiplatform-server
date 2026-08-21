/**
 * EventHub Context（base.eventhub）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>SSE 技术事件广播（emitter 管理 / 心跳 / 过滤订阅 / 信封与 id 分配）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>纯技术广播组件，fire-and-forget；SSE 双通道（/api/events 平台通知、
 * /api/agent-events agent 流）只共用本传输内核。无表、无错误码前缀（事件名册见
 * docs/spec/SSE事件清单.md；信封与通道语义见 ADR-0001）。base 区不发 SSE——
 * 通知由业务编排层在副作用落定后发射。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>四层骨架（domain / application / infrastructure / endpoints）随片1 落位</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "EventHub", subDomain = SubDomain.GENERIC)
package com.aieducenter.aiplatform.base.eventhub;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
