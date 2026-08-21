package com.aieducenter.aiplatform.business.project.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 门驳回命令（A3 §3/§5）：驳回一律停留当前阶段，reason 必填——驳回反馈是前端
 * 展示与沟通纪要（A5）的共同来源。
 *
 * @param reason 驳回理由（必填）
 */
public record StageRejectCommand(

        @NotBlank(message = "驳回理由不能为空")
        @Size(max = 1000, message = "驳回理由长度不能超过1000")
        String reason
) {
}
