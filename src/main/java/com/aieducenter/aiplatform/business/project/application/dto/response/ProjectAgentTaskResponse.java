package com.aieducenter.aiplatform.business.project.application.dto.response;

import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;

/**
 * 项目智能体任务响应（runId 随任务响应同值返回，ADR-0001；该运行全部流事件携带）。
 *
 * @param runId     运行标识（挂 agent 流 ?runId= 的锚）
 * @param sessionId 引擎会话标识（跨运行寻址；引擎拒绝时为空）
 * @param engine    承接引擎（注册表键）
 * @param role      角色卡（code）
 * @param roleName  角色卡名
 * @param stage     任务所处阶段名
 * @param accepted  引擎是否接受
 */
public record ProjectAgentTaskResponse(
        String runId,
        String sessionId,
        String engine,
        RolePreset role,
        String roleName,
        String stage,
        boolean accepted
) {
}
