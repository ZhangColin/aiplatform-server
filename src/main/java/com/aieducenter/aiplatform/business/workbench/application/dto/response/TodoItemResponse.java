package com.aieducenter.aiplatform.business.workbench.application.dto.response;

import java.time.Instant;

/**
 * 待办条目（A2 §4/§5）：需要人处理的事项——各处已有状态的实时计算式投影，
 * 非落库实体。
 *
 * @param type      待办型：dev = AGENT_WAIT / GATE_PENDING / TASK_SUBMITTED /
 *                  RETEST_READY / WORKSPACE_PROVISION_FAILED；opc = NEW_TASK /
 *                  TASK_REJECTED（任务型随 A4 #26 接线，谓词照 A4 §7 澄清表）
 * @param projectId 项目标识（TSID 十进制字符串——待办的导航锚点）
 * @param refId     型内引用键：AGENT_WAIT=waitId、GATE_PENDING/RETEST_READY=
 *                  projectId、任务型=taskId
 * @param title     中性短文本（投影层生成，不透出智能体产出内容）
 * @param createdAt 待办成形时刻（源状态时刻：等待点 raisedAt / 期门最近变更 /
 *                  任务迁移时刻，非拉取时刻）
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

    /** dev 视角：任务待确认（存在已提交任务）。 */
    public static final String TYPE_TASK_SUBMITTED = "TASK_SUBMITTED";

    /** dev 视角：可发复测（存在 FIXED Bug ∧ 无进行中测试任务 ∧ 无 in-flight 修复）。 */
    public static final String TYPE_RETEST_READY = "RETEST_READY";

    /** dev 视角：环境置备失败（工作区 failed 态，需重试）。 */
    public static final String TYPE_WORKSPACE_PROVISION_FAILED = "WORKSPACE_PROVISION_FAILED";

    /** opc 视角：新任务（指派给我 ∧ 已发布）。 */
    public static final String TYPE_NEW_TASK = "NEW_TASK";

    /** opc 视角：被驳回（指派给我 ∧ 执行中 ∧ 驳回过——重新提交即离开）。 */
    public static final String TYPE_TASK_REJECTED = "TASK_REJECTED";
}
