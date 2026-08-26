package com.aieducenter.aiplatform.business.project.application.dto.command;

import jakarta.validation.constraints.Size;

/**
 * 建项目命令（#39 创建精简：一句话创建）：requirement 是唯一入参——项目名由
 * LLM 异步生成（先落占位 {@code 未命名项目}，禁截取派生）、类型单模板服务端
 * 缺省、引擎读后台全局配置（#42）。
 *
 * @param requirement 初始需求描述（可空 = 缺省开场提示；前缀段自动 BA 的对话展开
 *                   起点，也是 LLM 取名的输入）
 */
public record CreateProjectCommand(

        @Size(max = 5000, message = "需求描述长度不能超过5000")
        String requirement
) {
}
