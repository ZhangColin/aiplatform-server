package com.aieducenter.aiplatform.business.task.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * bogus Bug 手工关闭命令（A4 §4，#27）：reason 必填——VERIFIED + closed_reason
 * （复测通过唯一关闭态的带理由别名动作，不加第四态）。
 */
public record CloseBugCommand(

        @NotBlank(message = "关闭理由不能为空")
        @Size(max = 1000, message = "关闭理由不能超过1000字")
        String reason
) {
}
