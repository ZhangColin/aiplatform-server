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

    /**
     * 角色卡分配（片5 编排层发射）：业务编排层选定角色卡后、run 下发前发射——
     * 底座无角色概念（systemPrompt/modelId 是入参），常量入册供编排层引用。
     */
    public static final String ROLE_ASSIGNED = "role-assigned";

    /**
     * 知识检索命中（A5 §4，编排层发射）：run 下发前单点检索注入的命中清单——
     * payload 带 items（前端呈现「本单命中第一单的 PRD」），常量入册供编排层引用。
     */
    public static final String KNOWLEDGE_RETRIEVED = "knowledge-retrieved";

    /** 命中条目清单（knowledge-retrieved；元素 = {kind, projectName, title, snippet?}）。 */
    public static final String KNOWLEDGE_ITEMS_FIELD = "items";

    /** 命中条目键：素材类别（入库 kind 透出）。 */
    public static final String KNOWLEDGE_KIND_FIELD = "kind";

    /** 命中条目键：来源项目名（跨项目命中是特性，A5 §3）。 */
    public static final String KNOWLEDGE_PROJECT_NAME_FIELD = "projectName";

    /** 命中条目键：素材标题。 */
    public static final String KNOWLEDGE_TITLE_FIELD = "title";

    /** 命中条目键：命中块文本。 */
    public static final String KNOWLEDGE_SNIPPET_FIELD = "snippet";

    /** 角色卡标识（RolePreset 枚举名）。 */
    public static final String ROLE_FIELD = "role";

    /** 角色卡展示标签。 */
    public static final String ROLE_LABEL_FIELD = "roleLabel";

    /** 角色卡所处阶段（无 OPEN 期时为终态段名）。 */
    public static final String ROLE_STAGE_FIELD = "stage";

    /** 承接运行的引擎名（注册表键）。 */
    public static final String ROLE_ENGINE_FIELD = "engine";

    /** 等待点稳定标识（wait-raised 补发 / wait-settled payload 用）。 */
    public static final String WAIT_ID_FIELD = "waitId";

    /** 等待点关闭结果（answered / approved / denied / deferred / cancelled）。 */
    public static final String WAIT_OUTCOME_FIELD = "outcome";

    /** 通用寻址键：引擎会话标识（平台帧 payload；与 wait-raised 契约键同值单源）。 */
    public static final String SESSION_FIELD = WAIT_SESSION_FIELD;

    /** 通用自述键：承接运行的引擎名（平台帧 payload；与角色卡帧的引擎键同值单源）。 */
    public static final String ENGINE_FIELD = ROLE_ENGINE_FIELD;

    /** task-finish 的结煞语键（end / error / cancelled）。 */
    public static final String FINISH_FIELD = "finish";

    /** 平台终止的 task-finish 结煞语（票 #38：cancelRun / deny cap 平台权威终态帧）。 */
    public static final String FINISH_CANCELLED = "cancelled";

    /** 运行终止联动的 wait-settled outcome 值（票 #38——SSE 契约新值，WaitOutcome 枚举不动）。 */
    public static final String OUTCOME_CANCELLED = "cancelled";

    private AgentEventTypes() {
    }
}
