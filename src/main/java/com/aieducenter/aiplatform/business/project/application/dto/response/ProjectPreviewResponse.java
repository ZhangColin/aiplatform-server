package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 预览响应：工作区端口真实暴露后的可访问 URL（localhost 主机端口）。
 *
 * @param url 预览地址（如 http://localhost:30080）
 */
public record ProjectPreviewResponse(String url) {
}
