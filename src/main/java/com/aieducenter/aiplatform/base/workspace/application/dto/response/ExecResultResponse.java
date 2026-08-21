package com.aieducenter.aiplatform.base.workspace.application.dto.response;

/**
 * 工作区命令执行结果（进程三要素原样归一化；exitCode 非 0 是命令自身失败，不是环境故障）。
 */
public record ExecResultResponse(String stdout, String stderr, int exitCode) {
}
