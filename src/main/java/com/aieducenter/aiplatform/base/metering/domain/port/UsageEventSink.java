package com.aieducenter.aiplatform.base.metering.domain.port;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;

/**
 * 用量上报端口（CLIENT 直调，非应用事件——上报要幂等/重试语义，ADR-0001
 * 「计量走直调上报」）。埋点方（agentengine 适配器 / 业务编排）构造
 * {@link UsageEvent} 直调本端口；{@code eventId} 幂等：重复上报不重复计入
 * （first-write-wins），调用方可安全重试。
 *
 * <p>平台内起步 = 进程内适配器；迁出独立计量服务时本端口换 REST 适配器，
 * 协议与调用方不动（A1 §2.1，端口即边界）。</p>
 */
@Port(PortType.CLIENT)
public interface UsageEventSink {

    /**
     * 上报一条用量事件（同步落库；同 eventId 已存在则幂等吸收，不抛错）。
     */
    void report(UsageEvent event);
}
