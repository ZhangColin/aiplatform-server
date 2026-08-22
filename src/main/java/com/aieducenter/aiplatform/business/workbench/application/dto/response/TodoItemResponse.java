package com.aieducenter.aiplatform.business.workbench.application.dto.response;

import java.time.Instant;

/**
 * 待办条目（A2 §4/§5）：需要人处理的事项——各处已有状态的实时计算式投影，
 * 非落库实体。
 *
 * @param type      待办型：AGENT_WAIT / GATE_PENDING（任务型 TASK_SUBMITTED /
 *                  RETEST_READY / NEW_TASK / TASK_REJECTED 随 A4 接线）
 * @param projectId 项目标识（TSID 十进制字符串——待办的导航锚点）
 * @param refId     型内引用键：AGENT_WAIT=waitId、GATE_PENDING=projectId、任务型=taskId
 * @param title     中性短文本（投影层生成，不透出智能体产出内容）
 * @param createdAt 待办成形时刻（源状态时刻：等待点 raisedAt / 期门最近变更，非拉取时刻）
 */
public record TodoItemResponse(
        String type,
        String projectId,
        String refId,
        String title,
        Instant createdAt) {

    /** dev 视角：智能体等待答复（base.agentengine pending 等待点）。 */
    public static final String TYPE_AGENT_WAIT = "AGENT_WAIT";

    /** dev 视角：门待拍板（期门就绪）。 */
    public static final String TYPE_GATE_PENDING = "GATE_PENDING";
}
