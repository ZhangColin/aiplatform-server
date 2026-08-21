package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 建项目响应（项目详情 + 前缀段自动 BA 的运行标识——前端挂 agent 流 ?runId= 的锚）。
 *
 * @param project  项目详情（起始段 BA、第 1 期 OPEN；含主链定义数据与门就绪）
 * @param runId    自动 BA 运行标识（引擎拒绝/异常时仍返回项目，accepted=false）
 * @param accepted BA 运行是否被引擎接受
 */
public record ProjectCreatedResponse(
        ProjectDetailResponse project,
        String runId,
        boolean accepted
) {
}
