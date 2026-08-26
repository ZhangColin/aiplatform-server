package com.aieducenter.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;

/**
 * 改名命令（#43 需求端右栏「项目信息」inline 改名）：名称后改的显式动作——
 * 占位名（#39 取名在飞/失败回落）与已具名均可改。
 *
 * <p>校验口径与创建一致：空白拒绝内聚在聚合（PRJ_005，本命令不重复）；
 * 长度上限 100 归本命令层守门（DB 列长同源，超限 400）。</p>
 *
 * @param name 新项目名（必填，1-100 字）
 */
public record RenameProjectCommand(

        @Schema(description = "新项目名（空白拒绝 PRJ_005，长度上限 100 与建项目同口径）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Size(max = Project.NAME_MAX_LENGTH, message = "项目名长度不能超过100")
        String name
) {
}
