package com.aieducenter.aiplatform.base.agentengine.domain.model;

/**
 * runTask 的同步结果（A1 §1.2）：异步语义——立即返回，干活过程经 sink 回调。
 * {@code accepted} = 会话已建立（引擎已接单）；失败时 sessionId 为 null，
 * 失败原因经 sink 的 error 事件表达，不抛异常。
 */
public record RunResult(String runId, String sessionId, boolean accepted) {

    public static RunResult rejected(String runId) {
        return new RunResult(runId, null, false);
    }
}
