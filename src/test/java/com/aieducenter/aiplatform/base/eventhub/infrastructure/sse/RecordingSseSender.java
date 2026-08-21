package com.aieducenter.aiplatform.base.eventhub.infrastructure.sse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 测试夹具：记录每个 emitter 收到的帧与发送尝试数；可对指定 emitter 恒抛异常模拟断连。
 * 内核测试与通道应用服务测试共用（白盒验证通道语义的接线）。
 */
public final class RecordingSseSender implements SseSender {

    private final List<SseEmitter> brokenEmitters = CollUtil.newArrayList();
    private final Map<SseEmitter, List<SseServerEvent>> received = MapUtil.newHashMap();
    private final Map<SseEmitter, AtomicInteger> attemptsPerEmitter = MapUtil.newHashMap();
    private final AtomicInteger attempts = new AtomicInteger();

    public void breakEmitter(SseEmitter emitter) {
        brokenEmitters.add(emitter);
    }

    public int attempts() {
        return attempts.get();
    }

    public int attemptsFor(SseEmitter emitter) {
        return attemptsPerEmitter.getOrDefault(emitter, new AtomicInteger()).get();
    }

    public List<SseServerEvent> framesOf(SseEmitter emitter) {
        return received.getOrDefault(emitter, List.of());
    }

    /** 只取事件帧（排除心跳注释帧）。 */
    public List<SseServerEvent> eventFramesOf(SseEmitter emitter) {
        return framesOf(emitter).stream().filter(frame -> frame.id() != null).toList();
    }

    @Override
    public void send(SseEmitter emitter, SseServerEvent event) throws IOException {
        attempts.incrementAndGet();
        attemptsPerEmitter.computeIfAbsent(emitter, key -> new AtomicInteger()).incrementAndGet();
        if (brokenEmitters.contains(emitter)) {
            throw new IOException("broken pipe");
        }
        received.computeIfAbsent(emitter, key -> CollUtil.newArrayList()).add(event);
    }
}
