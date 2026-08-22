package com.aieducenter.aiplatform.business.task.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 任务驳回命令（A4 §6）：reason 必填——驳回反馈是执行方重做的输入（REST 面
 * @NotBlank 先行，聚合不变量同码兜底）。
 */
public record RejectTaskCommand(

        @NotBlank(message = "驳回理由不能为空")
        @Size(max = 1000, message = "驳回理由不能超过1000字")
        String reason
) {
}
