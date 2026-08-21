package com.aieducenter.aiplatform.base.workspace.domain.model;

/**
 * 工作区内执行一条命令的结果：进程三要素原样归一化（后端不解释输出）。
 * exitCode = 0 表示命令成功；非 0 是命令自身的失败，不是环境故障。
 */
public record ExecResult(String stdout, String stderr, int exitCode) {

    public boolean ok() {
        return exitCode == 0;
    }
}
