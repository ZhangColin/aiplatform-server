package com.aieducenter.aiplatform.base.eventhub.infrastructure.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 线格式帧（内核内部用语）：事件帧（id + name + data）或心跳注释帧（comment）。
 *
 * <p>内核以本值对象组装与测试发送内容，{@link #toBuilder()} 才转 Spring 的
 * {@link SseEmitter.SseEventBuilder}——Spring builder 不可内省，测试缝留在本类型。</p>
 *
 * @since 0.1.0
 */
public record SseServerEvent(String id, String name, Object data, String comment) {

    public static SseServerEvent of(String id, String name, Object data) {
        return new SseServerEvent(id, name, data, null);
    }

    public static SseServerEvent comment(String comment) {
        return new SseServerEvent(null, null, null, comment);
    }

    /**
     * 转 Spring SSE 事件 builder（发送缝的最后一寸）。
     */
    public SseEmitter.SseEventBuilder toBuilder() {
        SseEmitter.SseEventBuilder builder = SseEmitter.event();
        if (id != null) {
            builder.id(id);
        }
        if (name != null) {
            builder.name(name);
        }
        if (data != null) {
            builder.data(data);
        }
        if (comment != null) {
            builder.comment(comment);
        }
        return builder;
    }
}
