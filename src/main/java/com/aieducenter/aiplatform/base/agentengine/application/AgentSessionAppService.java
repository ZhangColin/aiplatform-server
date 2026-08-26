package com.aieducenter.aiplatform.base.agentengine.application;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aieducenter.aiplatform.base.agentengine.application.dto.response.AgentSessionResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentSession;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentSessionRepository;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient;

/**
 * agent 会话查询用例（片2a）：按 workspaceId 寻址、跨重启存活的验证面
 * （{@code agt_agent_sessions} 落库，服务重启后照常可查——B0 蓝图片2 验收）。
 */
@Service
public class AgentSessionAppService {

    private final AgentSessionRepository sessionRepository;
    private final WorkspaceHandleClient workspaceHandleClient;

    public AgentSessionAppService(AgentSessionRepository sessionRepository,
                                  WorkspaceHandleClient workspaceHandleClient) {
        this.sessionRepository = sessionRepository;
        this.workspaceHandleClient = workspaceHandleClient;
    }

    /**
     * 工作区全部会话（新起在前）。工作区不存在由 workspace 侧抛 WSP_001（404）。
     */
    @Transactional(readOnly = true)
    public List<AgentSessionResponse> listByWorkspace(String workspaceId) {
        long id = workspaceHandleClient.handleOf(workspaceId).workspaceId().id();
        return sessionRepository.findByWorkspaceIdOrderByCreatedAtDesc(id).stream()
                .map(AgentSessionAppService::toResponse)
                .toList();
    }

    /**
     * 单查会话（sessionId 全局寻址）：任务回填续跑前的存活校验入口（A1 §3.2
     * 陈旧防护——会话已亡跳过不抛）。
     */
    @Transactional(readOnly = true)
    public Optional<AgentSessionResponse> session(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .map(AgentSessionAppService::toResponse);
    }

    /**
     * 登记会话并回答「是否首见」（#48 对话智能体的 session-created 发射口径）：
     * 已登记则刷新最近运行（ranOn）。跨上下文登记（非 runTask 建会话的内核，如
     * chatagent）走本应用层口——引擎自述 engine 值（注册表键 / UsageEvent.engine 同值）。
     */
    @Transactional
    public boolean recordIfAbsent(String workspaceId, String engine, String sessionId,
                                  String runId) {
        long numericWorkspaceId = workspaceHandleClient.handleOf(workspaceId)
                .workspaceId().id();
        AgentSession existing = sessionRepository.findBySessionId(sessionId).orElse(null);
        boolean firstSeen = existing == null;
        AgentSession session = firstSeen
                ? AgentSession.open(numericWorkspaceId, engine, sessionId, runId)
                : existing;
        session.ranOn(runId);
        sessionRepository.save(session);
        return firstSeen;
    }

    private static AgentSessionResponse toResponse(AgentSession session) {
        return new AgentSessionResponse(
                session.getSessionId(),
                Long.toString(session.getWorkspaceId()),
                session.getEngine(),
                session.getLastRunId(),
                session.getCreatedAt());
    }
}
