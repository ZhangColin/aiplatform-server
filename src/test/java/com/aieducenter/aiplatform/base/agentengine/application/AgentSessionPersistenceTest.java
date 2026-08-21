package com.aieducenter.aiplatform.base.agentengine.application;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentSession;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentSessionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * agent 会话落库（票 #20 验收：服务重启后会话可寻址）：agt_agent_sessions 真实
 * 落库 + 按 workspaceId 寻址查询 + (engine, session_id) 唯一——重启接回的验证面
 * （记录在库，重启后照常可查；B0 §5.2 副作用以真实状态为准）。
 */
@SpringBootTest
class AgentSessionPersistenceTest {

    private static final long WORKSPACE_ID = 987654321L;

    @Autowired
    private AgentSessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM agt_agent_sessions WHERE workspace_id = ?",
                WORKSPACE_ID);
    }

    @Test
    void given_opened_session_when_saved_then_row_addressable_by_workspace() {
        sessionRepository.save(
                AgentSession.open(WORKSPACE_ID, "opencode", "ses_persist_1", "run-1"));

        // 真实状态：行在库、字段落位
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agt_agent_sessions WHERE session_id = 'ses_persist_1'",
                Integer.class);
        assertThat(count).isEqualTo(1);
        // 重启接回的查询面：新仓储实例语义下按 workspaceId 寻址（重启后同一查询）
        List<AgentSession> sessions = sessionRepository
                .findByWorkspaceIdOrderByCreatedAtDesc(WORKSPACE_ID);
        assertThat(sessions).extracting(AgentSession::getSessionId)
                .containsExactly("ses_persist_1");
        assertThat(sessions.get(0).getEngine()).isEqualTo("opencode");
        assertThat(sessions.get(0).getLastRunId()).isEqualTo("run-1");
    }

    @Test
    void given_reuse_when_ranOn_then_last_run_refreshed_in_db() {
        AgentSession session = sessionRepository.save(
                AgentSession.open(WORKSPACE_ID, "dsh", "dsh-persist-1", "run-1"));

        session.ranOn("run-2");
        sessionRepository.save(session);

        String lastRun = jdbcTemplate.queryForObject(
                "SELECT last_run_id FROM agt_agent_sessions WHERE id = " + session.getId(),
                String.class);
        assertThat(lastRun).isEqualTo("run-2");
    }

    @Test
    void given_find_by_session_id_when_exists_then_unique_addressing() {
        sessionRepository.save(
                AgentSession.open(WORKSPACE_ID, "opencode", "ses_persist_2", "run-1"));

        assertThat(sessionRepository.findBySessionId("ses_persist_2")).isPresent();
        assertThat(sessionRepository.findBySessionId("ses_none")).isEmpty();
    }
}
