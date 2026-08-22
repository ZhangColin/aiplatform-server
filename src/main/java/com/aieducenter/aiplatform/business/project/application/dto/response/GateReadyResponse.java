package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.Instant;

/**
 * 门就绪条目（A2 §5 workbench GATE_PENDING 投影源）：期 OPEN ∧ 当前阶段有门 ∧
 * 门禁（计数 ∧ 业务谓词）满足的项目——与项目详情 gate 视图同一裁决口径。
 *
 * @param projectId  项目标识（TSID 十进制字符串；待办 refId 同值）
 * @param stageLabel 门所在阶段展示标签（如「需求梳理」）
 * @param gateActor  拍板方（user / platform）
 * @param readySince 期最近一次变更时刻（审计 last-modified——门就绪时刻的近似
 *                   锚点，排序展示用；JPA 审计墙钟按部署时区换算为 Instant）
 */
public record GateReadyResponse(
        String projectId,
        String stageLabel,
        String gateActor,
        Instant readySince) {
}
