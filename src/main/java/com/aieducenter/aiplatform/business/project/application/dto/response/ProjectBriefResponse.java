package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 项目简报（A4 §7 最小项目上下文）：项目名 + 预览地址——opc 任务卡片的
 * 必带字段（预览地址工作区记录派生，工作区已亡置 null）。
 */
public record ProjectBriefResponse(
        String projectId,
        String name,
        String previewUrl
) {
}
