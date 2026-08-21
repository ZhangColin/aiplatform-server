package com.aieducenter.aiplatform.base.agentengine.domain.port;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.RunResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;

/**
 * 开发智能体适配层端口（CONTEXT.md「开发智能体适配层」）：抹平各引擎差异的薄
 * adapter，五方法——runTask / pendingQuestions / replyQuestions / replyPermission /
 * health。systemPrompt 与 modelId 是 {@link AgentTaskCommand} 入参，适配层不含
 * 任何角色概念。
 *
 * <p>runTask 异步：立即返回 {@link RunResult}，干活过程（思考/输出/工具/补丁/
 * 完成）经 sink 回调；agent 可能中途向用户提问或请求权限——经 pendingQuestions /
 * replyQuestions / replyPermission 交互（等待点统一模型归片2b，本端口方法名不动）。
 * 引擎能力差异如实暴露（A1 §1.5 能力矩阵）：headless 引擎 pendingQuestions 恒空、
 * reply 为 no-op。</p>
 *
 * <p>实现：OpenCodeAdapter（容器内 serve 的 HTTP 接入）/ DshAdapter（环境 exec 的
 * headless 一次性任务）；经 {@code AgentEngineRegistry} 显式注册（不靠 Spring
 * bean 名）。</p>
 */
@Port(PortType.CLIENT)
public interface CodingAgentAdapter {

    /** 引擎名（注册键，与注册表 / 能力矩阵 / UsageEvent.engine 同值）。 */
    String engine();

    /** 引擎显示名（能力矩阵行）。 */
    String label();

    /** 引擎一句话说明（能力矩阵行，界面可见）。 */
    String note();

    /** 能力如实暴露（A1 §1.5 矩阵）：有无问答通道（false = pendingQuestions 恒空、reply no-op）。 */
    boolean supportsQuestions();

    /** 能力如实暴露：有无权限审批通道（false = replyPermission no-op）。 */
    boolean supportsPermissions();

    /**
     * 起一个任务（异步）：建会话（或复用 command.sessionId 续跑）、注入 systemPrompt
     * 与模型档位、后台发提示词；过程事件经 sink 回调。用量埋点：支持 usage 的引擎
     * 在 run 结束直调 UsageEventSink 上报 run 级恰一条（求和是适配器内部实现，
     * A1 §2.3）；无 usage 的引擎（dsh headless）不发——无数据不造数。
     */
    RunResult runTask(WorkspaceHandle handle, AgentTaskCommand command, Consumer<AgentEvent> sink);

    /**
     * 该会话里 agent 等待用户回答的问题（引擎载荷原样透出，底座不解释）；
     * 无问答能力的引擎恒空。
     */
    List<Map<String, Object>> pendingQuestions(WorkspaceHandle handle, String sessionId);

    /** 回答问题：answers 按问题顺序，每项 = 该问题选中的标签列表（custom 输入也作为标签）。 */
    void replyQuestions(WorkspaceHandle handle, String sessionId, String requestId,
                        List<List<String>> answers);

    /** 审批回复（人做决策：agent 请求权限时由用户批准/拒绝）。 */
    void replyPermission(WorkspaceHandle handle, String sessionId, String permissionId,
                         boolean approve);

    /** 引擎是否就绪（opencode = serve 可达；dsh = CLI 可用）。 */
    boolean health(WorkspaceHandle handle);
}
