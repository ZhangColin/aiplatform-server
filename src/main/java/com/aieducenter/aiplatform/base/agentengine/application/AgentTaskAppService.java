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
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentSession;
import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.RunResult;
import com.aieducenter.aiplatform.base.agentengine.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentSessionRepository;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;

import lombok.extern.slf4j.Slf4j;

/**
 * 任务下发与交互用例（片2a）：底座任务端点的编排——runId 生成（ADR-0001：任务端点
 * 生成并随响应返回）、引擎路由（注册表显式寻址）、会话落库（复用校验 + 登记续跑）、
 * agent 流桥（适配器回调 → agent-events 通道透传）。
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
    private final AgentSessionRepository sessionRepository;
    private final AgentStreamAppService streamAppService;

    public AgentTaskAppService(WorkspaceHandleClient workspaceHandleClient,
                               AgentEngineRegistry registry,
                               AgentSessionRepository sessionRepository,
                               AgentStreamAppService streamAppService) {
        this.workspaceHandleClient = workspaceHandleClient;
        this.registry = registry;
        this.sessionRepository = sessionRepository;
        this.streamAppService = streamAppService;
    }

    /**
     * 下发任务：runId 生成 → 引擎路由 → 适配器异步起跑（过程事件透传 agent 流通道）
     * → 会话登记/续跑。accepted=false 不落会话，失败原因经 error 事件表达。
     */
    public AgentTaskResponse dispatch(String workspaceId, AgentTaskDispatchCommand command) {
        WorkspaceHandle handle = workspaceHandleClient.handleOf(workspaceId);
        AgentEngineRegistry.RegisteredEngine engine = command.engine() == null || command.engine().isBlank()
                ? registry.defaultEngine() : registry.require(command.engine());
        String runId = newRunId();
        if (command.sessionId() != null && !command.sessionId().isBlank()) {
            requireSessionForReuse(handle, engine.info().name(), command.sessionId());
        }
        // 计量归属兜底：底座端点无业务 subject，以 workspaceId（中性键）归属；
        // dims 无业务维度可透传，空集
        AgentTaskCommand taskCommand = new AgentTaskCommand(
                runId, command.prompt(), command.systemPrompt(), command.modelId(),
                command.sessionId(), new UsageContext(workspaceId, Map.of()));

        RunResult result = engine.adapter().runTask(handle, taskCommand, streamSink(workspaceId));
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

    // ---------- 内部 ----------

    /** agent 流桥：适配器回调透传（payload 已带 runId；补底座中性寻址 workspaceId）。 */
    private Consumer<AgentEvent> streamSink(String workspaceId) {
        return event -> {
            Map<String, Object> payload = new LinkedHashMap<>(event.payload());
            payload.put(AgentStreamAppService.WORKSPACE_FIELD, workspaceId);
            streamAppService.publish(event.type(), payload);
        };
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
