package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.Instant;
import java.util.Map;

import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;

/**
 * 项目等待点响应（底座 WaitPointResponse 的项目视角投影：projectId 来自路径，
 * 其余字段桥接自等待点通道，A1 §1.1 统一模型）。
 *
 * @param waitId       等待点稳定标识（答复/转任务的引用键）
 * @param kind         种类：QUESTION（问答）/ PERMISSION（权限）
 * @param status       状态（列表只回 PENDING）
 * @param summary      适配器提取的中性短文本
 * @param sessionId    引擎会话标识
 * @param runId        所属运行
 * @param engineRef    引擎侧请求/权限 id
 * @param body         引擎载荷原样（选项等，底座不解释）
 * @param settleOutcome 关闭结果（PENDING 时为空）
 * @param raisedAt     出现时间
 * @param settledAt    关闭时间
 */
public record ProjectWaitResponse(
        String waitId,
        WaitKind kind,
        WaitStatus status,
        String summary,
        String sessionId,
        String runId,
        String engineRef,
        Map<String, Object> body,
        WaitOutcome settleOutcome,
        Instant raisedAt,
        Instant settledAt
) {
}
