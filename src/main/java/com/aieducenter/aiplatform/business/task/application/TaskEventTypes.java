package com.aieducenter.aiplatform.business.task.application;

/**
 * 任务平台通知事件名册常量（ADR-0001：代码侧每 BC 一个 EventTypes 常量类，
 * 禁止字符串字面量散落；正本见 docs/spec/SSE事件清单.md·通道一）。任务状态
 * 每次迁移（含创建落已发布）发一条 task-updated——任务平台布局挂通知通道
 * 过滤自己的任务即重拉；Bug 状态不单独发（随任务与 REST）。
 */
public final class TaskEventTypes {

    /** 任务状态迁移（含创建落已发布）。 */
    public static final String TASK_UPDATED = "task-updated";

    // ---------- payload 契约键（SSE事件清单·通道一） ----------

    /** 关联字段（通知通道 streamId 同值）。 */
    public static final String PROJECT_ID_FIELD = "projectId";

    /** 任务标识。 */
    public static final String TASK_ID_FIELD = "taskId";

    /** 迁移后状态（枚举名）。 */
    public static final String STATUS_FIELD = "status";

    private TaskEventTypes() {
    }
}
