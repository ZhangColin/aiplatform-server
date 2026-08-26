package com.aieducenter.aiplatform.base.agentengine.domain.port;

import java.util.List;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;

/**
 * 等待点答复通道（片2b 窄面，#48 抽出）：能承接 {@code settle} 派发的引擎侧组件——
 * {@link CodingAgentAdapter} 全量实现本面（编码引擎路径行为不变），对话智能体等
 * 非编码内核（ADR-0002 双轨分野）实现本面即可接入同一等待点协议（settle 即续跑、
 * deny cap 终止），不必也不得进编码引擎能力矩阵（registry / Project.engine 词汇）。
 *
 * <p>寻址：按 {@link #engine()} 名（与 agt_agent_sessions.engine 同值）经
 * {@code WaitResponderDirectory} 索引——等待点模型引擎无关，答复派发只认会话自述的
 * 引擎名。</p>
 */
@Port(PortType.CLIENT)
public interface WaitResponder {

    /** 引擎名（与 CodingAgentAdapter.engine 同值约定：注册键 / UsageEvent.engine）。 */
    String engine();

    /** 回答问题：answers 按问题顺序，每项 = 该问题选中的标签列表（custom 输入也作为标签）。 */
    void replyQuestions(WorkspaceHandle handle, String sessionId, String requestId,
                        List<List<String>> answers);

    /** 审批回复（人做决策：agent 请求权限时由用户批准/拒绝）。 */
    void replyPermission(WorkspaceHandle handle, String sessionId, String permissionId,
                         boolean approve);

    /**
     * 终止会话当前运行（deny cap 平台终止路径，A1 §1.3：同 run 内 permission deny
     * 计数达阈值 → 平台主动终止，防审批循环）。无运行可终止返回 false。
     */
    boolean abort(WorkspaceHandle handle, String sessionId);
}
