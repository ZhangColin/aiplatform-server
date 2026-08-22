package com.aieducenter.aiplatform.business.project.application.dto.command;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 答复等待点命令（与底座 wait 端点同构，A1 §1.1 三型封闭；type 字面量的唯一
 * 真值是底座 {@code WaitSettleCommand.TYPE_*} 常量）。type=deferred 转任务：
 * 关等待点 + 建任务（A1 §3.1，#27）——task 子载荷必填（任务的标题/内容/指派）。
 *
 * @param type    答复型：answer / permission / deferred
 * @param answers 问答选项（type=answer 必填，选项 label）
 * @param approve 批准与否（type=permission 必填）
 * @param note    备注（type=deferred 可带，并入任务内容）
 * @param task    转任务建任务入参（type=deferred 必填）
 */
public record ProjectWaitSettleCommand(

        @NotBlank(message = "type 不能为空")
        String type,

        List<List<String>> answers,

        Boolean approve,

        @Size(max = 1000, message = "备注不能超过1000字")
        String note,

        @Valid
        DeferredTaskPayload task
) {

    /** 转任务建任务入参（content 缺省取等待点摘要 + note，A1 §3.1）。 */
    public record DeferredTaskPayload(

            @NotBlank(message = "任务标题不能为空")
            @Size(max = 200, message = "任务标题不能超过200字")
            String title,

            @Size(max = 5000, message = "任务内容不能超过5000字")
            String content,

            @NotNull(message = "指派账号不能为空")
            Long assigneeAccountId
    ) {
    }
}
