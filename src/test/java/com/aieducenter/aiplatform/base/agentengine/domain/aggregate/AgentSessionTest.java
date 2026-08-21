package com.aieducenter.aiplatform.base.agentengine.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * agent 会话聚合：登记不变量 + 续跑刷新（agt_agent_sessions 的领域行为）。
 */
class AgentSessionTest {

    @Test
    void given_valid_fields_when_open_then_session_recorded() {
        AgentSession session = AgentSession.open(123L, "opencode", "ses_abc", "run-1");

        assertThat(session.getWorkspaceId()).isEqualTo(123L);
        assertThat(session.getEngine()).isEqualTo("opencode");
        assertThat(session.getSessionId()).isEqualTo("ses_abc");
        assertThat(session.getLastRunId()).isEqualTo("run-1");
    }

    @Test
    void given_blank_fields_when_open_then_rejected() {
        assertThatThrownBy(() -> AgentSession.open(123L, "opencode", " ", "run-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(AgentEngineMessage.SESSION_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> AgentSession.open(0L, "opencode", "ses_abc", "run-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(AgentEngineMessage.SESSION_FIELDS_INCOMPLETE.message());
    }

    @Test
    void given_reuse_when_ranOn_then_last_run_refreshed() {
        AgentSession session = AgentSession.open(123L, "dsh", "dsh-1", "run-1");

        session.ranOn("run-2");

        assertThat(session.getLastRunId()).isEqualTo("run-2");
    }

    @Test
    void given_blank_run_when_ranOn_then_rejected() {
        AgentSession session = AgentSession.open(123L, "opencode", "ses_abc", "run-1");

        assertThatThrownBy(() -> session.ranOn(" "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(AgentEngineMessage.SESSION_FIELDS_INCOMPLETE.message());
    }
}
