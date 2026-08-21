package com.aieducenter.aiplatform.base.agentengine.domain.model;

/**
 * agent 流事件名册常量（ADR-0001：代码侧每 BC 一个 EventTypes 常量类，禁止字符串
 * 字面量散落；正本见 docs/spec/SSE事件清单.md·通道二）。
 *
 * <p>只收录本 BC 发射的封闭集合——平台事件；引擎透传事件（text / reasoning /
 * patch / tool / step-start / step-finish 等）的 type 是引擎 part 类型原样
 * （开放集合，不设常量），payload 的 {@code data} 键内为 part 原样。</p>
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

    /**
     * 等待点出现（片2b）：适配器发现通道发出——payload 带 runId/sessionId/kind/
     * summary/engineRef/data（引擎载荷原样）。底座 sink 桥接收到后落库即闭
     * （不透传 SSE——wait-raised 的发射归编排层桥接，票 #22）。
     */
    public static final String WAIT_RAISED = "wait-raised";

    // ---------- wait-raised payload 契约键（适配器上报与 sink 桥接共用的唯一真值） ----------

    /** 关联字段（事件 id 的 streamId 同值）。 */
    public static final String WAIT_RUN_FIELD = "runId";

    /** 引擎会话标识原样。 */
    public static final String WAIT_SESSION_FIELD = "sessionId";

    /** 等待点种类（WaitKind 枚举名：QUESTION / PERMISSION）。 */
    public static final String WAIT_KIND_FIELD = "kind";

    /** 适配器提取的中性短文本。 */
    public static final String WAIT_SUMMARY_FIELD = "summary";

    /** 引擎侧请求/权限 id（settle 答复派发键）。 */
    public static final String WAIT_ENGINE_REF_FIELD = "engineRef";

    /** 引擎载荷原样（底座不解释）。 */
    public static final String WAIT_DATA_FIELD = "data";

    /**
     * 等待点关闭（片2b）：底座不发（发射归编排层桥接 #22，payload 含 outcome）；
     * 常量入册供桥接方引用（SSE事件清单·通道二，禁止字符串字面量散落）。
     */
    public static final String WAIT_SETTLED = "wait-settled";

    private AgentEventTypes() {
    }
}
