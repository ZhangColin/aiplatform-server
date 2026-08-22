package com.aieducenter.aiplatform.business.task.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 建测试任务命令（A4 §6）：type 固定 TEST 不收（v1 单值，扩展时加映射）；
 * assignee 必填（指派与领取分离，v1 只指派）。waitId 是转任务来源的程序化
 * 入参，REST 面不收（回填随 #27）。
 */
public record CreateTaskCommand(

        @NotBlank(message = "任务标题不能为空")
        @Size(max = 200, message = "任务标题不能超过200字")
        String title,

        @NotBlank(message = "任务内容不能为空")
        String content,

        @NotNull(message = "指派账号不能为空")
        Long assigneeAccountId
) {
}
