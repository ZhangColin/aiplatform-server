package com.aieducenter.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 门驳回命令（A3 §3/§5）：驳回一律停留当前阶段，reason 必填——驳回反馈是前端
 * 展示与沟通纪要（A5）的共同来源。
 *
 * @param reason            驳回理由（必填）
 * @param requirementChange 涉及需求变更标记（#46）：true 时驳回意见同时回流 BA 触发
 *                          PRD 修订——v1 显式标记不做语义自动判定，缺省 false
 *                          （纯 Demo 意见不惊动 BA）；仅 Demo 确认门（G2）表单呈现
 */
public record StageRejectCommand(

        @NotBlank(message = "驳回理由不能为空")
        @Size(max = 1000, message = "驳回理由长度不能超过1000")
        String reason,

        @Schema(description = "涉及需求变更标记（Demo 确认门驳回表单）：true 时驳回意见同时回流 BA"
                + " 触发 PRD 修订（document-updated 可观测），Demo 修正以新 PRD 为准；"
                + "缺省 false——纯 Demo 意见不惊动 BA。显式标记，不做语义自动判定",
                defaultValue = "false")
        boolean requirementChange
) {
}
