package com.aieducenter.aiplatform.business.project.application;

import java.util.HashMap;
import java.util.Map;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;

/**
 * {@code stage-changed} payload 拼装（SSE事件清单·通道一的契约形状收口一处）：
 * 必带 projectId/stage/stageLabel；门决策带 approved/rejected（互斥），驳回带
 * reason；编排触发的推进（建项目起始段、DEV→TEST）两者皆不带。
 */
final class StageChangedPayload {

    private StageChangedPayload() {
    }

    /** 门通过推进（stage = 通过后迁入的阶段；末门 = 终态关闭）。 */
    static Map<String, Object> approved(Long projectId, String stage) {
        return withFlags(projectId, stage, ProjectEventTypes.APPROVED_FIELD, null);
    }

    /** 门驳回停留（stage 停留当前，reason 必带——前端展示驳回理由）。 */
    static Map<String, Object> rejected(Long projectId, String stage, String reason) {
        return withFlags(projectId, stage, ProjectEventTypes.REJECTED_FIELD, reason);
    }

    /** 编排触发的阶段落位/推进（建项目起始段 BA、首个测试任务的 DEV→TEST）。 */
    static Map<String, Object> plain(Long projectId, String stage) {
        return withFlags(projectId, stage, null, null);
    }

    private static Map<String, Object> withFlags(Long projectId, String stage,
                                                 String decisionFlag, String reason) {
        String stageLabel = ProjectMainChain.definition().find(stage)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE))
                .label();
        Map<String, Object> payload = new HashMap<>();
        payload.put(ProjectEventTypes.PROJECT_ID_FIELD, projectId.toString());
        payload.put(ProjectEventTypes.STAGE_FIELD, stage);
        payload.put(ProjectEventTypes.STAGE_LABEL_FIELD, stageLabel);
        if (decisionFlag != null) {
            payload.put(decisionFlag, true);
        }
        if (reason != null) {
            payload.put(ProjectEventTypes.REASON_FIELD, reason);
        }
        return payload;
    }
}
