package com.aieducenter.aiplatform.base.chatagent.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.port.ChatAgentClient;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentWorkspaceClient;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope.ChatAgentResumeGate;

import lombok.extern.slf4j.Slf4j;

/**
 * 对话智能体用例（#45 平台接线，#48 等待点双向桥）：{@link ChatAgentClient} 的流桥
 * ——适配器逐帧回调注入命令的流关联字段（如 projectId，对齐编码引擎 run 的
 * AgentRunContext 口径：底座不解释、逐帧透传）后经既有 agent 流通道
 * （{@link AgentStreamAppService}，runId 锚定）发射，前端零新增协议。converse 同步
 * 阻塞至本轮结束（挂起轮在 wait-raised 后软终点返回——见 client 挂起语义）。
 *
 * <p><b>流桥拦截（#48，对齐 AgentTaskAppService.streamSink 语义）</b>：命令带
 * workspaceId 时——{@code wait-raised} 落库成平台等待点（raiseFromEvent，幂等），
 * 落库成功补 waitId 再发 SSE（挂起 REST 可查、SSE 可见）；run 终态
 * （task-finish/error，超时也是 error 表达）联动其 PENDING 等待点 → EXPIRED。
 * 落库/联动失败只记日志不拖垮流桥（护栏与编码引擎同款）。无 workspaceId（本地
 * 兜底）不拦截——纯透传（#45 既有口径）。{@link #sink} 公开供 settle 续跑桥
 * （resume 流同口径拦截：再挂起/终态联动）复用。</p>
 *
 * <p>发射失败护栏：单帧发射异常只记日志不断流（SSE 是「让 UI 活」的面，不承担
 * 正确性，SSE事件清单）；对话本身的成败以 {@link ChatAgentReply} / 异常表达。</p>
 */
@Service
@Slf4j
public class ChatAgentAppService {

    /** 对话智能体的引擎自述名（UsageEvent.engine / role-assigned 双轨标识；正本在此，
     *  {@code ChatAgentSessionRecorder} 反向引用——业务层经应用层取用，不进 infra）。 */
    public static final String ENGINE = "agentscope";

    private final ChatAgentClient chatAgentClient;
    private final AgentStreamAppService streamAppService;
    private final AgentWaitAppService waitAppService;
    private final ChatAgentWorkspaceClient workspaceClient;
    private final ChatAgentResumeGate resumeGate;

    public ChatAgentAppService(ChatAgentClient chatAgentClient,
            AgentStreamAppService streamAppService, AgentWaitAppService waitAppService,
            ChatAgentWorkspaceClient workspaceClient, ChatAgentResumeGate resumeGate) {
        this.chatAgentClient = chatAgentClient;
        this.streamAppService = streamAppService;
        this.waitAppService = waitAppService;
        this.workspaceClient = workspaceClient;
        this.resumeGate = resumeGate;
    }

    /**
     * 跑一轮对话：过程帧（task-start → 过程 → task-finish/error；挂起轮在
     * wait-raised 后软终点）实时进 agent 流通道（payload 已带 runId；关联字段随帧
     * 注入）。
     */
    public ChatAgentReply converse(ChatAgentCommand command) {
        return chatAgentClient.converse(command,
                sink(command.workspaceId(), command.streamCorrelation()));
    }

    /**
     * 静默轮（#39 取名等平台内部轻调用）：不经流桥——无 SSE 帧、不落等待点、无
     * 终态联动（对话智能体通道的「无 UI」用法，前端零感知）；计量照报
     * （usageContext 非空时，与 {@link #converse} 同口径）。失败照抛
     * （DomainException），吞不吞归调用方——取名回落占位即吞。
     */
    public ChatAgentReply converseSilently(ChatAgentCommand command) {
        return chatAgentClient.converse(command, event -> { });
    }

    /**
     * 异步跑一轮对话（#40 BA 访谈编排入口）：提交 {@link ChatAgentResumeGate} 串行
     * 执行后即返回（REST 快返回，过程帧经流桥进 SSE；失败经 error 帧表达，异常由
     * 闸吞掉记日志）。与 settle 续跑共闸——单会话一次一轮（新轮与续跑并发会互踩
     * 同一 (userId, sessionId) 状态槽位）；提交前先复活闸（新 run 承接会话即复活，
     * client 内部的复活在排队任务里才执行——来不及救被关闸挡掉的提交）。返回是否
     * 提交成功（复活后与关闸竞态的极端窗口才会 false——「被拒的 run 没有创建事实」
     * 归调用方口径）。
     */
    public boolean converseAsync(ChatAgentCommand command) {
        return converseAsync(command, null);
    }

    /**
     * 带轮闸的异步轮（#40 访谈化解路由的执行时复核）：排到的执行时刻先过
     * {@code turnGuard}——false 表示本轮已被更新的事实取代（如前序续跑刚挂起新
     * 提问、调用方已把本轮文本按答复 settle），跳过对话。提交时与执行时之间会话
     * 状态可能前移（串行闸只保证不并发，不保证快照不老），在悬提问化解这类
     * 「执行一刻才知道能不能开轮」的编排靠本缝兜竞态窗口。
     */
    public boolean converseAsync(ChatAgentCommand command,
            Predicate<ChatAgentCommand> turnGuard) {
        resumeGate.reopen(command.sessionId());
        boolean submitted = resumeGate.submit(command.sessionId(), () -> {
            if (turnGuard == null || turnGuard.test(command)) {
                converse(command);
            }
        });
        if (!submitted) {
            log.warn("[chatagent] 异步轮提交被拒（session={}，复活后与关闸竞态），丢弃本轮",
                    command.sessionId());
        }
        return submitted;
    }

    /** 流桥 sink：带 workspaceId 时拦截 wait-raised 落库与终态联动（见类注释）。 */
    public Consumer<AgentEvent> sink(String workspaceId, Map<String, Object> correlation) {
        boolean bridged = workspaceId != null && !workspaceId.isBlank();
        return event -> {
            try {
                if (bridged && AgentEventTypes.WAIT_RAISED.equals(event.type())) {
                    onWaitRaised(workspaceId, correlation, event);
                    return;
                }
                publish(event, withCorrelation(event.payload(), correlation));
                if (bridged && isRunTerminal(event)) {
                    expireRunOf(event);
                }
            }
            catch (RuntimeException e) {
                log.warn("[chatagent] 流帧处理失败（{}）：{}", event.type(), e.getMessage());
            }
        };
    }

    // ---------- 内部 ----------

    private void onWaitRaised(String workspaceId, Map<String, Object> correlation,
                              AgentEvent event) {
        WaitPointResponse raised = waitAppService.raiseFromEvent(
                workspaceClient.handleOf(workspaceId).workspaceId().id(), event.payload());
        // 落库即闭 + 关联方补发（带 waitId；发射归调用方关联，底座端点口径同编码引擎）
        Map<String, Object> addressed = withCorrelation(event.payload(), correlation);
        addressed.put(AgentEventTypes.WAIT_ID_FIELD, raised.waitId());
        streamAppService.publish(event.type(), addressed);
    }

    private boolean isRunTerminal(AgentEvent event) {
        return AgentEventTypes.TASK_FINISH.equals(event.type())
                || AgentEventTypes.ERROR.equals(event.type());
    }

    private void expireRunOf(AgentEvent event) {
        Object runId = event.payload().get(AgentStreamAppService.RUN_FIELD);
        if (runId != null) {
            waitAppService.expireRun(runId.toString());
        }
    }

    private void publish(AgentEvent event, Map<String, Object> payload) {
        try {
            streamAppService.publish(event.type(), payload);
        }
        catch (RuntimeException e) {
            log.warn("[chatagent] 流帧发射失败（{}）：{}", event.type(), e.getMessage());
        }
    }

    /** 关联字段注入（透传不解释；帧序在前——寻址字段不覆盖帧本体字段）。 */
    private static Map<String, Object> withCorrelation(Map<String, Object> payload,
                                                       Map<String, Object> correlation) {
        if (correlation == null || correlation.isEmpty()) {
            return new LinkedHashMap<>(payload);
        }
        Map<String, Object> addressed = new LinkedHashMap<>(correlation);
        addressed.putAll(payload);
        return addressed;
    }
}
