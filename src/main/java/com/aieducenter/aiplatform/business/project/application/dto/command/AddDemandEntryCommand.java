package com.aieducenter.aiplatform.business.project.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.aieducenter.aiplatform.business.project.domain.enums.DemandEntryKind;
import com.aieducenter.aiplatform.business.project.domain.enums.DemandSource;

/**
 * 需求池入池命令（A3 §4：随时可记，验收前后都能提）。
 *
 * @param content 收件内容（必填）
 * @param kind    条目类型（可空——收件时不强分类）
 * @param source  来源（可空 = 用户缺省）
 */
public record AddDemandEntryCommand(

        @NotBlank(message = "需求池内容不能为空")
        @Size(max = 2000, message = "需求池内容长度不能超过2000")
        String content,

        DemandEntryKind kind,

        DemandSource source
) {
}
