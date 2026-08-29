package com.aieducenter.aiplatform.base.agentengine.application;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentWait;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentWaitRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 等待点落库（票 #21 验收：落库、waitId 跨重启稳定）：agt_pending_waits 真实
 * 落库——waitId 主键寻址、(session_id, engine_ref) 唯一（raise 幂等）、状态迁移
 * 落库、按 workspaceId/runId 聚合查询（B0 §5.2 副作用以真实状态为准）。
 */
@SpringBootTest
class AgentWaitPersistenceTest {

    private static final long WORKSPACE_ID = 987654322L;
    private static final long OTHER_WORKSPACE_ID = 987654323L;
    private static final Instant RAISED_AT = Instant.parse("2026-08-22T08:00:00Z");

    @Autowired
    private AgentWaitRepository waitRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM agt_pending_waits WHERE workspace_id IN (?, ?)",
                WORKSPACE_ID, OTHER_WORKSPACE_ID);
    }

    @Test
    void given_raised_wait_when_saved_then_row_addressable_by_wait_id() {
        AgentWait wait = waitRepository.save(AgentWait.raise(WORKSPACE_ID, "ses_w1",
                "run-1", WaitKind.QUESTION, "que_1", "用哪个框架?",
                Map.of("id", "que_1", "questions", java.util.List.of()), RAISED_AT));

        // 真实状态：行在库、waitId 即主键（重启后凭同一 waitId 可寻址）；
        // status 为 BaseEnum 整数码（1=PENDING）
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM agt_pending_waits WHERE wait_id = ?",
                Integer.class, wait.getWaitId());
        assertThat(status).isEqualTo(WaitStatus.PENDING.getCode());
        assertThat(waitRepository.findById(wait.getWaitId())).isPresent();

        // 跨会话聚合面：PENDING 按工作区命中
        assertThat(waitRepository
                .findByWorkspaceIdAndStatusOrderByRaisedAtDesc(WORKSPACE_ID, WaitStatus.PENDING))
                .extracting(AgentWait::getWaitId)
                .containsExactly(wait.getWaitId());
    }

    @Test
    void given_settled_wait_when_saved_then_transition_persisted() {
        AgentWait wait = waitRepository.save(AgentWait.raise(WORKSPACE_ID, "ses_w1",
                "run-2", WaitKind.PERMISSION, "per_1", "执行 rm -rf",
                Map.of("id", "per_1"), RAISED_AT));

        wait.settle(WaitOutcome.DENIED, RAISED_AT.plusSeconds(60));
        waitRepository.save(wait);

        // 聚合面不再命中 PENDING；deny 计数命中（deny cap 的读侧）
        assertThat(waitRepository
                .findByWorkspaceIdAndStatusOrderByRaisedAtDesc(WORKSPACE_ID, WaitStatus.PENDING))
                .isEmpty();
        assertThat(waitRepository.countByRunIdAndStatusAndSettleOutcome(
                "run-2", WaitStatus.SETTLED, WaitOutcome.DENIED)).isEqualTo(1);
        assertThat(waitRepository.findByRunIdAndStatus("run-2", WaitStatus.SETTLED))
                .hasSize(1);
    }

    @Test
    void given_duplicate_pending_ref_when_saved_then_partial_unique_rejected() {
        waitRepository.save(AgentWait.raise(WORKSPACE_ID, "ses_w2", "run-3",
                WaitKind.QUESTION, "que_dup", null, null, RAISED_AT));

        // raise 幂等约束：同 (session_id, engine_ref) 的第二行 PENDING 被库层拒绝
        assertThatThrownBy(() -> waitRepository.save(AgentWait.raise(WORKSPACE_ID,
                "ses_w2", "run-4", WaitKind.QUESTION, "que_dup", null, null, RAISED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void given_terminal_row_of_same_ref_when_saved_then_new_pending_row_coexists() {
        // 终态行保留为历史，不挡同挂起的再登记（引擎侧超时误伤后新 run 重检到）
        AgentWait expired = waitRepository.save(AgentWait.raise(WORKSPACE_ID, "ses_w2",
                "run-3", WaitKind.QUESTION, "que_hist", null, null, RAISED_AT));
        expired.expire(RAISED_AT.plusSeconds(30));
        waitRepository.save(expired);

        AgentWait reopened = waitRepository.save(AgentWait.raise(WORKSPACE_ID, "ses_w2",
                "run-9", WaitKind.QUESTION, "que_hist", null, null,
                RAISED_AT.plusSeconds(60)));

        assertThat(waitRepository.findById(reopened.getWaitId())).isPresent();
        assertThat(waitRepository.findBySessionIdAndEngineRefAndStatus(
                "ses_w2", "que_hist", WaitStatus.PENDING)).isPresent();
        // 聚合面：再登记行可见（答得了），终态行不干扰
        assertThat(waitRepository
                .findByWorkspaceIdAndStatusOrderByRaisedAtDesc(WORKSPACE_ID, WaitStatus.PENDING))
                .extracting(AgentWait::getWaitId)
                .contains(reopened.getWaitId());
    }

    @Test
    void given_pending_across_workspaces_when_listed_then_newest_first_across_projects() {
        // 跨项目待办查询面（A2 §60）：工作台 AGENT_WAIT 投影源，全量 PENDING 新者在前
        AgentWait older = waitRepository.save(AgentWait.raise(WORKSPACE_ID, "ses_c1",
                "run-c1", WaitKind.QUESTION, "que_c1", null, null, RAISED_AT));
        AgentWait settled = waitRepository.save(AgentWait.raise(WORKSPACE_ID, "ses_c1",
                "run-c2", WaitKind.PERMISSION, "per_c2", null, null,
                RAISED_AT.plusSeconds(30)));
        settled.settle(WaitOutcome.APPROVED, RAISED_AT.plusSeconds(60));
        waitRepository.save(settled);
        AgentWait fresh = waitRepository.save(AgentWait.raise(OTHER_WORKSPACE_ID, "ses_c3",
                "run-c3", WaitKind.QUESTION, "que_c3", null, null,
                RAISED_AT.plusSeconds(90)));

        // 本地库可能有他途 PENDING 残留（冒烟/BA 运行），只断言本测试两行的
        // 相对序与终态隔离——「新者在前」语义不变，不受环境数据影响
        assertThat(waitRepository.findByStatusOrderByRaisedAtDesc(WaitStatus.PENDING))
                .extracting(AgentWait::getWaitId)
                .containsSubsequence(fresh.getWaitId(), older.getWaitId()) // 新者在前
                .doesNotContain(settled.getWaitId()); // 终态不混入
    }

    @Test
    void given_session_cleanup_when_cancel_then_row_terminal() {
        waitRepository.save(AgentWait.raise(WORKSPACE_ID, "ses_w3", "run-5",
                WaitKind.QUESTION, "que_5", null, null, RAISED_AT));
        AgentWait stale = waitRepository.save(AgentWait.raise(WORKSPACE_ID, "ses_w3",
                "run-5", WaitKind.PERMISSION, "per_5", null, null, RAISED_AT));

        // 复用会话下发前的残留清理：会话名下 PENDING 全部取消落库
        for (AgentWait wait : waitRepository
                .findBySessionIdAndStatus("ses_w3", WaitStatus.PENDING)) {
            wait.cancel(RAISED_AT.plusSeconds(1));
            waitRepository.save(wait);
        }

        assertThat(waitRepository.findBySessionIdAndStatus("ses_w3", WaitStatus.PENDING))
                .isEmpty();
        assertThat(waitRepository.findById(stale.getWaitId()))
                .hasValueSatisfying(w -> assertThat(w.getStatus())
                        .isEqualTo(WaitStatus.CANCELLED));
    }
}
