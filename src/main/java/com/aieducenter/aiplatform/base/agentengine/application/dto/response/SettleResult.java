package com.aieducenter.aiplatform.base.agentengine.application.dto.response;

/**
 * settle 用例结果（票 #38）：关闭后的等待点投影 + 会话引擎（deny cap 终止派发键）+
 * 是否达 deny cap。跨上下文契约（business.project 桥接消费），故入 dto/response。
 *
 * <p><b>denyCapped=true 时调用方须接续终止</b>——经
 * {@code AgentTaskAppService#terminateRun}（与 cancelRun 共用路径：abort + 收口 +
 * 平台终态帧），在编排层的 wait-settled(outcome) 帧之后调用可保帧序
 * wait-settled × N → task-finish(cancelled) 最后落地（前端 wait-settled 一律把
 * run 拉回 running）。</p>
 */
public record SettleResult(WaitPointResponse settled, String engine, boolean denyCapped) {
}
