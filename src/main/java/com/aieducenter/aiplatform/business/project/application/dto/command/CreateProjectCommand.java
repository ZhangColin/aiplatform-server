package com.aieducenter.aiplatform.business.project.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

/**
 * 建项目命令（对话建项目：名字 + 初始需求描述，引擎取全局配置 + 类型）。
 *
 * @param name        项目名
 * @param type        项目类型（可空 = 官网缺省；分类字段，不决定过程——主链唯一）
 * @param engine      开发智能体引擎（可空 = 后台全局配置的生效引擎，票 #42：平台统一
 *                    配置、未配置时注册表缺省 opencode；显式传入创建时固化进项目记录）
 * @param requirement 初始需求描述（可空 = 缺省开场提示；前缀段自动 BA 的对话展开起点）
 */
public record CreateProjectCommand(

        @NotBlank(message = "项目名不能为空")
        @Size(max = 100, message = "项目名长度不能超过100")
        String name,

        ProjectType type,

        String engine,

        @Size(max = 5000, message = "需求描述长度不能超过5000")
        String requirement
) {
}
