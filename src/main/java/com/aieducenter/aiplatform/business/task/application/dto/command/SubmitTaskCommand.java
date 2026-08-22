package com.aieducenter.aiplatform.business.task.application.dto.command;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.aieducenter.aiplatform.business.task.domain.enums.BugSeverity;

/**
 * 任务提交命令（A4 §3 两种形状，应用层裁决）：首轮测试 = {@code report + bugs}
 * （空清单允许——测试全过）；复测 = {@code report + results}（逐 Bug 结果）。
 * {@code bugs} 与 {@code results} 二选一（含 null 判定：bugs 非 null 即首轮、
 * results 非 null 即复测），同给或同缺 400 TASK_006。
 */
public record SubmitTaskCommand(

        @NotBlank(message = "测试报告不能为空")
        @Size(max = 5000, message = "测试报告不能超过5000字")
        String report,

        @Valid
        List<BugPayload> bugs,

        @Valid
        List<RetestResultPayload> results
) {

    /** 首轮 Bug 清单条目（确认时入库 tsk_bugs，A4 §3）。 */
    public record BugPayload(

            @NotBlank(message = "Bug 标题不能为空")
            @Size(max = 200, message = "Bug 标题不能超过200字")
            String title,

            @Size(max = 2000, message = "Bug 描述不能超过2000字")
            String description,

            @Size(max = 2000, message = "复现步骤不能超过2000字")
            String reproSteps,

            @NotNull(message = "Bug 严重档位不能为空")
            BugSeverity severity
    ) {
    }

    /** 复测逐条结果（确认时翻态：pass=true → VERIFIED / false → 退回 OPEN）。 */
    public record RetestResultPayload(

            @NotNull(message = "bugId 不能为空")
            Long bugId,

            boolean pass,

            @Size(max = 500, message = "复测备注不能超过500字")
            String note
    ) {
    }
}
