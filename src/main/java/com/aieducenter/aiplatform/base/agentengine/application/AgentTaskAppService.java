package com.aieducenter.aiplatform.base.agentengine.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentPermissionCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentQuestionReplyCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.AgentTaskDispatchCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentTaskResponse;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.SettleResult;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentSession;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentWait;
import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.RunResult;
import com.aieducenter.aiplatform.base.agentengine.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentSessionRepository;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentWaitRepository;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;

import lombok.extern.slf4j.Slf4j;

/**
 * 任务下发与交互用例（片2a + 片2b 等待点接线）：底座任务端点的编排——runId 生成
 * （ADR-0001：任务端点生成并随响应返回）、引擎路由（注册表显式寻址）、会话落库
 * （复用校验 + 登记续跑）、agent 流桥（适配器回调 → agent-events 通道透传）。
 *
 * <p>片2b 等待点接线（A1 §1.3 避雷落点）：复用 sessionId 下发前清理该会话残留
 * 等待点（{@link AgentWaitAppService#cancelSessionWaits}，「有则先清理再跑」）；
 * 流桥拦截 {@code wait-raised} 落库不透传（SSE 发射归编排层桥接，票 #22）、
 * run 终态事件（task-finish/error，含超时）联动其 PENDING 等待点 → EXPIRED。</p>
 *
 * <p>运行终止（票 #38）：{@link #cancelRun}（用户显式终止）与 {@link #terminateRun}
 * （deny cap 平台终止共用路径）——abort + 收口 + 平台权威终态帧（wait-settled
 * (outcome=cancelled) 先于 task-finish(finish=cancelled)，帧序硬约束）。</p>
 *
 * <p>事务形态：引擎交互（HTTP / docker exec，秒到分钟级）不进事务；会话登记是
 * 单行落库（仓储自带事务）。片5 business.project 接管业务编排时经
 * {@code CodingAgentAdapter} 端口直调（systemPrompt/modelId/UsageContext 由业务层
 * 组装，A1 §2.3）——底座任务端点以 workspaceId 作计量归属兜底（中性键，零业务概念）。</p>
 */
@Service
@Slf4j
public class AgentTaskAppService {

    private final WorkspaceHandleClient workspaceHandleClient;
    private final AgentEngineRegistry registry;
    private final EngineConfigAppService engineConfigAppService;
    private final AgentSessionRepository sessionRepository;
    private final AgentWaitRepository waitRepository;
    private final WaitResponderDirectory responders;
    private final AgentStreamAppService streamAppService;
    private final AgentWaitAppService waitAppService;

    public AgentTaskAppService(WorkspaceHandleClient workspaceHandleClient,
                               AgentEngineRegistry registry,
                               EngineConfigAppService engineConfigAppService,
                               AgentSessionRepository sessionRepository,
                               AgentWaitRepository waitRepository,
                               WaitResponderDirectory responders,
                               AgentStreamAppService streamAppService,
                               AgentWaitAppService waitAppService) {
        this.workspaceHandleClient = workspaceHandleClient;
        this.registry = registry;
        this.engineConfigAppService = engineConfigAppService;
        this.sessionRepository = sessionRepository;
        this.waitRepository = waitRepository;
        this.responders = responders;
        this.streamAppService = streamAppService;
        this.waitAppService = waitAppService;
    }

    /**
     * 下发任务（底座任务端点形态）：runId 生成 → 引擎路由 → 适配器异步起跑（过程事件
     * 透传 agent 流通道）→ 会话登记/续跑。accepted=false 不落会话，失败原因经
     * error 事件表达。
     */
    public AgentTaskResponse dispatch(String workspaceId, AgentTaskDispatchCommand command) {
        return dispatch(workspaceId, command, null);
    }

    /**
     * 下发任务（片5 业务编排入口，{@link AgentRunContext}）：业务侧带上 runId /
     * 计量归属 / 流关联字段下发，其余编排与会话登记 / 等待点接线与底座端点同流程
     * ——runId 缺省生成、UsageContext 缺省 workspaceId 兜底、关联字段随帧注入
     * agent 流 payload（带关联时 wait-raised 落库后补发）。
     */
    public AgentTaskResponse dispatch(String workspaceId, AgentTaskDispatchCommand command,
                                      AgentRunContext runContext) {
        return dispatch(workspaceId, command, runContext, null);
    }

    /**
     * 下发任务（编排事件观察者缝，#27 修复编排链）：{@code eventObserver} 在底座
     * 流桥处理完每帧后同线程回调（含过程事件与终态 task-finish/error）——编排方
     * 持 sink 收终态的入口（A4 §4「链是进程内 sink 回调」）。观察者异常不拖垮
     * 底座桥（SSE 透传/等待点联动照常），编排方链护栏自行兜底。
     */
    public AgentTaskResponse dispatch(String workspaceId, AgentTaskDispatchCommand command,
                                      AgentRunContext runContext,
                                      Consumer<AgentEvent> eventObserver) {
        WorkspaceHandle handle = workspaceHandleClient.handleOf(workspaceId);
        // 缺省引擎 = 后台全局配置（票 #42：服务端统一配置，读库不缓存即时生效）
        AgentEngineRegistry.RegisteredEngine engine = command.engine() == null || command.engine().isBlank()
                ? engineConfigAppService.activeEngine() : registry.require(command.engine());
        String runId = runContext != null && runContext.runId() != null && !runContext.runId().isBlank()
                ? runContext.runId() : newRunId();
        if (command.sessionId() != null && !command.sessionId().isBlank()) {
            requireSessionForReuse(handle, engine.info().name(), command.sessionId());
            // 复用前清理残留等待点（A1 §1.3：死状态等待点会让续跑任务全卡）
            waitAppService.cancelSessionWaits(command.sessionId());
        }
        // 计量归属兜底：底座端点无业务 subject，以 workspaceId（中性键）归属；
        // 业务编排入口（runContext）自带 subject=projectId + 业务 dims（A1 §2.4）
        UsageContext usageContext = runContext != null && runContext.usageContext() != null
                ? runContext.usageContext() : new UsageContext(workspaceId, Map.of());
        AgentTaskCommand taskCommand = new AgentTaskCommand(
                runId, command.prompt(), command.systemPrompt(), command.modelId(),
                command.sessionId(), usageContext);

        RunResult result = engine.adapter().runTask(handle, taskCommand,
                observed(streamSink(workspaceId,
                        runContext == null ? null : runContext.streamCorrelation()), eventObserver));
        if (result.accepted()) {
            recordSession(handle.workspaceId().id(), engine.info().name(),
                    result.sessionId(), runId);
        }
        return new AgentTaskResponse(runId, result.sessionId(), engine.info().name(),
                result.accepted());
    }

    /**
     * 会话内待答问题（引擎载荷原样，底座不解释；无问答能力的引擎恒空）。
     */
    public List<Map<String, Object>> pendingQuestions(String workspaceId, String sessionId) {
        WorkspaceHandle handle = workspaceHandleClient.handleOf(workspaceId);
        AgentSession session = requireSession(handle, sessionId);
        return registry.require(session.getEngine()).adapter()
                .pendingQuestions(handle, sessionId);
    }

    /**
     * 回答问题（agent 继续干活）。
     */
    public void replyQuestions(String workspaceId, String sessionId, String requestId,
                               AgentQuestionReplyCommand command) {
        WorkspaceHandle handle = workspaceHandleClient.handleOf(workspaceId);
        AgentSession session = requireSession(handle, sessionId);
        try {
            registry.require(session.getEngine()).adapter()
                    .replyQuestions(handle, sessionId, requestId, command.answers());
        } catch (RuntimeException e) {
            throw engineRequestFailed(e);
        }
    }

    /**
     * 权限审批回复。
     */
    public void replyPermission(String workspaceId, String sessionId, String permissionId,
                                AgentPermissionCommand command) {
        WorkspaceHandle handle = workspaceHandleClient.handleOf(workspaceId);
        AgentSession session = requireSession(handle, sessionId);
        try {
            registry.require(session.getEngine()).adapter()
                    .replyPermission(handle, sessionId, permissionId, command.approve());
        } catch (RuntimeException e) {
            throw engineRequestFailed(e);
        }
    }

    /**
     * 引擎健康（opencode = serve 可达；dsh = CLI 可用）。
     */
    public boolean health(String workspaceId, String engine) {
        WorkspaceHandle handle = workspaceHandleClient.handleOf(workspaceId);
        return registry.require(engine).adapter().health(handle);
    }

    /**
     * 终止运行（票 #38 运行终止，工作台顶栏「终止」/审批卡「终止任务」逃生口）：
     * runId 解析（该 run 名下等待点行自带 sessionId 优先 → 会话 lastRunId 回退，
     * 工作区须相符）→ {@link #terminateRun}。查无 AGT_011（404）；best-effort——
     * 重复终止空转 200（无 PENDING 无 wait-settled 帧，平台终态帧同值重发）。
     * 关联字段（如 projectId）随帧注入 agent 流 payload，缺省即底座 workspaceId 形态。
     */
    public void cancelRun(String workspaceId, String runId, Map<String, Object> correlation) {
        WorkspaceHandle handle = workspaceHandleClient.handleOf(workspaceId);
        AgentSession session = resolveRunSession(handle, runId);
        log.info("[agentengine] 运行终止 runId={} session={} engine={}（工作区 {}）",
                runId, session.getSessionId(), session.getEngine(), workspaceId);
        terminateRun(workspaceId, session.getEngine(), session.getSessionId(), runId,
                correlation);
    }

    /**
     * 平台终止路径（票 #38 统一）：abort + 等待点收口 + 平台权威终态帧——cancelRun
     * 与 deny cap 平台终止（{@link SettleResult#denyCapped()} 的
     * 调用方接续）共用。帧序硬约束：wait-settled(outcome=cancelled) × N 在前、
     * task-finish(finish=cancelled) 在后（前端 wait-settled 一律把 run 拉回 running，
     * 终态帧必须最后落地）。abort 引擎交互失败不外抛（best-effort 恒成行，dsh no-op
     * 同形态）；引擎自然帧照透不抑制。
     */
    public void terminateRun(String workspaceId, String engine, String sessionId, String runId,
                             Map<String, Object> correlation) {
        WorkspaceHandle handle = workspaceHandleClient.handleOf(workspaceId);
        boolean aborted = false;
        try {
            aborted = responders.require(engine).abort(handle, sessionId);
        } catch (RuntimeException e) {
            log.warn("[agentengine] run {} 平台终止引擎交互失败：{}", runId, e.getMessage());
        }
        if (!aborted) {
            log.warn("[agentengine] run {} 平台终止未生效（引擎侧无运行/终止失败/dsh 不支持）",
                    runId);
        }
        for (WaitPointResponse closed : waitAppService.expireRunReturning(runId)) {
            streamAppService.publish(AgentEventTypes.WAIT_SETTLED, withAddressing(Map.of(
                    AgentStreamAppService.RUN_FIELD, runId,
                    AgentEventTypes.WAIT_ID_FIELD, closed.waitId(),
                    AgentEventTypes.WAIT_OUTCOME_FIELD, AgentEventTypes.OUTCOME_CANCELLED),
                    workspaceId, correlation));
        }
        streamAppService.publish(AgentEventTypes.TASK_FINISH, withAddressing(Map.of(
                AgentStreamAppService.RUN_FIELD, runId,
                AgentEventTypes.SESSION_FIELD, sessionId,
                AgentEventTypes.ENGINE_FIELD, engine,
                AgentEventTypes.FINISH_FIELD, AgentEventTypes.FINISH_CANCELLED),
                workspaceId, correlation));
    }

    // ---------- 内部 ----------

    /**
     * 运行终止的 runId 解析：等待点行优先（行自带 sessionId，挂起 run 常态）→
     * {@code AgentSession.lastRunId} 回退（在飞无等待点 run，含 BA 对话轮）。两条
     * 路径都要求解析结果属于该工作区（否则 AGT_011，防跨项目寻址误终止）；
     * lastRunId 已被更新 run 覆盖时不做防御——abort 是会话粒度，终止该会话当前
     * 执行即语义（票 #38 grilling 定案）。
     */
    private AgentSession resolveRunSession(WorkspaceHandle handle, String runId) {
        long workspaceId = handle.workspaceId().id();
        List<AgentWait> runWaits = waitRepository.findByRunId(runId);
        if (!runWaits.isEmpty()) {
            String sessionId = runWaits.stream()
                    .filter(wait -> wait.getWorkspaceId() == workspaceId)
                    .map(AgentWait::getSessionId)
                    .findFirst()
                    .orElseThrow(AgentTaskAppService::runNotFound);
            return sessionRepository.findBySessionId(sessionId)
                    .orElseThrow(AgentTaskAppService::runNotFound);
        }
        return sessionRepository.findByWorkspaceIdAndLastRunId(workspaceId, runId)
                .orElseThrow(AgentTaskAppService::runNotFound);
    }

    private static ApplicationException runNotFound() {
        return new ApplicationException(AgentEngineMessage.RUN_NOT_FOUND);
    }

    /**
     * agent 流桥：适配器回调透传（payload 已带 runId；补底座中性寻址 workspaceId，
     * 带编排关联字段时一并注入）。片2b 拦截两类：{@code wait-raised} 落库即闭——
     * 中性调用方不透传（waitId 落库才存在，透传半成品事件无消费方），带关联字段的
     * 编排调用方（片5 业务桥）在落库成功后补发（发射归编排层的口径：底座只对
     * 带关联的调用方补发，底座端点行为不变）；run 终态（task-finish/error，超时
     * 也是 error 表达）联动其 PENDING 等待点 → EXPIRED 后照常透传。
     */
    private Consumer<AgentEvent> streamSink(String workspaceId, Map<String, Object> correlation) {
        boolean correlated = correlation != null && !correlation.isEmpty();
        return event -> {
            if (AgentEventTypes.WAIT_RAISED.equals(event.type())) {
                try {
                    WaitPointResponse raised = waitAppService.raiseFromEvent(
                            Long.parseLong(workspaceId), event.payload());
                    if (correlated && raised != null) {
                        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
                        payload.put(AgentEventTypes.WAIT_ID_FIELD, raised.waitId());
                        streamAppService.publish(event.type(),
                                withAddressing(payload, workspaceId, correlation));
                    }
                } catch (RuntimeException e) {
                    // 落库失败不拖垮流桥：等待点丢了可经引擎重查/重上报收敛
                    log.warn("[agentengine] wait-raised 落库失败（{}）：{}", workspaceId,
                            e.getMessage());
                }
                return;
            }
            Map<String, Object> payload = withAddressing(event.payload(), workspaceId, correlation);
            Object runId = payload.get(AgentStreamAppService.RUN_FIELD);
            if ((AgentEventTypes.TASK_FINISH.equals(event.type())
                    || AgentEventTypes.ERROR.equals(event.type()))
                    && runId != null) {
                try {
                    waitAppService.expireRun(runId.toString());
                } catch (RuntimeException e) {
                    // 联动失败不拖垮终态帧透传（与 wait-raised 落库护栏对称）
                    log.warn("[agentengine] run 终态等待点联动失败（{}）：{}", runId,
                            e.getMessage());
                }
            }
            streamAppService.publish(event.type(), payload);
        };
    }

    /** 观察者包装：底座桥处理完毕后回调；观察者异常只记日志不断流桥。 */
    private static Consumer<AgentEvent> observed(Consumer<AgentEvent> sink,
                                                 Consumer<AgentEvent> observer) {
        if (observer == null) {
            return sink;
        }
        return event -> {
            sink.accept(event);
            try {
                observer.accept(event);
            } catch (RuntimeException e) {
                log.warn("[agentengine] 编排事件观察者异常（{}）：{}", event.type(), e.getMessage());
            }
        };
    }

    /** 帧寻址注入：底座中性 workspaceId + 编排关联字段（如 projectId，透传不解释）。 */
    private static Map<String, Object> withAddressing(Map<String, Object> payload,
                                                       String workspaceId,
                                                       Map<String, Object> correlation) {
        Map<String, Object> addressed = new LinkedHashMap<>(payload);
        addressed.put(AgentStreamAppService.WORKSPACE_FIELD, workspaceId);
        if (correlation != null) {
            addressed.putAll(correlation);
        }
        return addressed;
    }

    /** 会话登记 / 续跑：返回的 sessionId 已登记则刷新最近运行（dsh 续跑换新会话即新登记）。 */
    private void recordSession(long workspaceId, String engine, String sessionId, String runId) {
        AgentSession session = sessionRepository.findBySessionId(sessionId)
                .orElseGet(() -> AgentSession.open(workspaceId, engine, sessionId, runId));
        session.ranOn(runId);
        sessionRepository.save(session);
    }

    /** 复用前校验：会话存在（AGT_002）且属于该工作区、引擎相符（AGT_003）。 */
    private AgentSession requireSessionForReuse(WorkspaceHandle handle, String engine,
                                                String sessionId) {
        AgentSession session = requireSession(handle, sessionId);
        if (!engine.equals(session.getEngine())) {
            throw new ApplicationException(AgentEngineMessage.SESSION_WORKSPACE_MISMATCH);
        }
        return session;
    }

    /** 交互前校验：会话存在（AGT_002）且属于该工作区（AGT_003）；引擎取会话行自述。 */
    private AgentSession requireSession(WorkspaceHandle handle, String sessionId) {
        AgentSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ApplicationException(AgentEngineMessage.SESSION_NOT_FOUND));
        if (session.getWorkspaceId() != handle.workspaceId().id()) {
            throw new ApplicationException(AgentEngineMessage.SESSION_WORKSPACE_MISMATCH);
        }
        return session;
    }

    private ApplicationException engineRequestFailed(RuntimeException e) {
        log.warn("[agentengine] 引擎交互失败：{}", e.getMessage());
        return new ApplicationException(AgentEngineMessage.ENGINE_REQUEST_FAILED, e.getMessage());
    }

    /** runId 生成（任务端点生成，ADR-0001）：TSID 十进制字符串（SSE id / 库列 / 日志共用）。 */
    private String newRunId() {
        return Long.toString(TsidGenerator.newInstance().generate());
    }
}
