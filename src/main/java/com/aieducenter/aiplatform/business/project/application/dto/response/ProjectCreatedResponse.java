package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 建项目响应（项目字段 + 前缀段自动 BA 的运行标识——前端挂 agent 流 ?runId= 的锚）。
 *
 * @param project  项目（起始段 BA、第 1 期 OPEN）
 * @param runId    自动 BA 运行标识（引擎拒绝/异常时仍返回项目，accepted=false）
 * @param accepted BA 运行是否被引擎接受
 */
public record ProjectCreatedResponse(
        ProjectResponse project,
        String runId,
        boolean accepted
) {
}
