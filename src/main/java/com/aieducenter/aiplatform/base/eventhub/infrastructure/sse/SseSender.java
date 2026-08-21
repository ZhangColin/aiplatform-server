package com.aieducenter.aiplatform.base.eventhub.infrastructure.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 发送缝：内核对一个 emitter 实际下发 SSE 帧的最后一跳。
 *
 * <p>生产实现 {@link #DIRECT} 直通 {@link SseEmitter#send}；测试注入失败/记录实现，
 * 使 fire-and-forget（发送失败只记日志）与帧内容可确定性验证。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface SseSender {

    SseSender DIRECT = (emitter, event) -> emitter.send(event.toBuilder());

    void send(SseEmitter emitter, SseServerEvent event) throws Exception;
}
