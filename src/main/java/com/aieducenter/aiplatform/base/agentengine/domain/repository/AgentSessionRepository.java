package com.aieducenter.aiplatform.base.agentengine.domain.repository;

import java.util.List;
import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentSession;

/**
 * agent 会话仓储：按 workspaceId 寻址（重启接回的查询面）与按 sessionId 续跑校验。
 */
public interface AgentSessionRepository extends BaseRepository<AgentSession, Long> {

    /** 引擎会话标识寻址（续跑缝的校验入口，(engine, sessionId) 唯一）。 */
    Optional<AgentSession> findBySessionId(String sessionId);

    /** 工作区全部会话，新起在前（重启后可寻址的验证面）。 */
    List<AgentSession> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);

    /** 最近运行寻址（票 #38 运行终止的 lastRunId 回退解析；工作区限定防跨区误终止）。 */
    Optional<AgentSession> findByWorkspaceIdAndLastRunId(Long workspaceId, String lastRunId);
}
