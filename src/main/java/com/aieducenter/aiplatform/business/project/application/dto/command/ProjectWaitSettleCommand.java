package com.aieducenter.aiplatform.business.project.application.dto.command;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * 答复等待点命令（与底座 wait 端点同构，A1 §1.1 三型封闭；type 字面量的唯一
 * 真值是底座 {@code WaitSettleCommand.TYPE_*} 常量）。Deferred 的转任务动作归
 * A4 修复编排链（票 #27），此端点只负责关闭等待点。
 *
 * @param type    答复型：answer / permission / deferred
 * @param answers 问答选项（type=answer 必填，选项 label）
 * @param approve 批准与否（type=permission 必填）
 * @param note    备注（type=deferred 可带）
 */
public record ProjectWaitSettleCommand(

        @NotBlank(message = "type 不能为空")
        String type,

        List<List<String>> answers,

        Boolean approve,

        String note
) {
}
