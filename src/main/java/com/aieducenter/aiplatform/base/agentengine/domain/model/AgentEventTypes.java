package com.aieducenter.aiplatform.base.agentengine.domain.model;

/**
 * agent 流事件名册常量（ADR-0001：代码侧每 BC 一个 EventTypes 常量类，禁止字符串
 * 字面量散落；正本见 docs/spec/SSE事件清单.md·通道二）。
 *
 * <p>只收录本 BC 发射的封闭集合——平台事件；引擎透传事件（text / reasoning /
 * patch / tool / step-start / step-finish 等）的 type 是引擎 part 类型原样
 * （开放集合，不设常量），payload 的 {@code data} 键内为 part 原样。
 * 等待点事件（wait-raised / wait-settled）随片2b 落位再录。</p>
 */
public final class AgentEventTypes {

    /** 运行开始（runId 随任务响应同值返回）。 */
    public static final String TASK_START = "task-start";

    /** 会话建立（复用 sessionId 续跑不发——会话早已建立）。 */
    public static final String SESSION_CREATED = "session-created";

    /** 运行失败（异步路径的失败表达，不抛异常）。 */
    public static final String ERROR = "error";

    /** 运行结束（finish = 引擎结煞语，如 end / error）。 */
    public static final String TASK_FINISH = "task-finish";

    private AgentEventTypes() {
    }
}
