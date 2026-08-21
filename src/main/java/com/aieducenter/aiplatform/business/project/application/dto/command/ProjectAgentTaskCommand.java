package com.aieducenter.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;

/**
 * 项目智能体任务命令（手动下任务，典型 DEV/ARCH；角色缺省取当前阶段默认角色卡）。
 *
 * @param prompt 任务内容（给智能体的指令）
 * @param role   角色卡（Integer code：1=BA 2=DEV 3=DELIVERY 4=ARCH 5=TEST 6=DEMO；
 *               可空 = 当前阶段默认角色，无默认角色的阶段必填 → 409 PRJ_004）
 */
public record ProjectAgentTaskCommand(

        @NotBlank(message = "任务内容不能为空")
        @Size(max = 10000, message = "任务内容长度不能超过10000")
        String prompt,

        @Schema(description = "角色卡 code：1=BA 2=DEV 3=DELIVERY 4=ARCH 5=TEST 6=DEMO；缺省取阶段默认")
        RolePreset role
) {
}
