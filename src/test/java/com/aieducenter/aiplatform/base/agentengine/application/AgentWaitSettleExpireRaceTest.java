package com.aieducenter.aiplatform.base.agentengine.application;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentWait;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentSessionRepository;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentWaitRepository;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * settle 落库与终态联动的竞态（票 #37）：真实库里确定性交错两写者——settle 契约
 * 「先引擎后落库」，引擎收到（尤其 reject）后 run 毫秒级收口，流桥的
 * {@code expireRun} 可在 settle 的 save 提交前完成 SELECT（读到的还是 PENDING），
 * 两写交错后写胜出：刚 SETTLED/DENIED 的行被改写为 EXPIRED、settle_outcome 归
 * null，deny cap 计数被低估。
 *
 * <p>确定性手法（不依赖 {@code Thread.sleep} 的时运）：动态代理包装真实
 * {@link AgentWaitRepository}，在联动路径的 SELECT 返回快照之后、写库之前，用
 * 独立连接（JdbcTemplate 自持事务并提交）把目标行改为 SETTLED/DENIED——即
 * 「settle 写者已在另一事务提交」的最小交错形态。被测的是生产代码路径
 * {@link AgentWaitAppService#expireRun}/{@link #cancelSessionWaits} 本身。</p>
 */
@SpringBootTest
class AgentWaitSettleExpireRaceTest {

    private static final long WORKSPACE_ID = 987654330L;
    private static final Instant NOW = Instant.parse("2026-08-29T08:00:00Z");

    @Autowired
    private AgentWaitRepository waitRepository;

    @Autowired
    private AgentSessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void cleanWaits() {
        jdbcTemplate.update("DELETE FROM agt_pending_waits WHERE workspace_id = ?",
                WORKSPACE_ID);
    }

    @Test
    void given_settle_committed_after_linkage_snapshot_when_expire_run_then_row_keeps_settled() {
        AgentWait pending = raisePending("ses_race", "run_race1", "per_race1");
        // 交错：联动 SELECT 拿到 PENDING 快照后、写库前，settle 写者（独立连接）先提交
        AgentWaitAppService raced = appServiceInterleavingAfterSnapshot(
                "findByRunIdAndStatus", () -> settleAsDeniedCommitted(pending.getWaitId()));

        int migrated = raced.expireRun("run_race1");

        // 后写不得胜出：行保持 SETTLED/DENIED，deny cap 计数含该次 DENIED
        assertThat(migrated).isZero();
        AgentWait row = waitRepository.findById(pending.getWaitId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(WaitStatus.SETTLED);
        assertThat(row.getSettleOutcome()).isEqualTo(WaitOutcome.DENIED);
        assertThat(waitRepository.countByRunIdAndStatusAndSettleOutcome(
                "run_race1", WaitStatus.SETTLED, WaitOutcome.DENIED)).isEqualTo(1);
    }

    @Test
    void given_settle_committed_after_cleanup_snapshot_when_cancel_session_then_row_keeps_settled() {
        AgentWait pending = raisePending("ses_race2", "run_race2", "per_race2");
        // 对称交错：复用会话清理的 SELECT 快照后，settle 写者先提交
        AgentWaitAppService raced = appServiceInterleavingAfterSnapshot(
                "findBySessionIdAndStatus", () -> settleAsDeniedCommitted(pending.getWaitId()));

        int cancelled = raced.cancelSessionWaits("ses_race2");

        assertThat(cancelled).isZero();
        AgentWait row = waitRepository.findById(pending.getWaitId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(WaitStatus.SETTLED);
        assertThat(row.getSettleOutcome()).isEqualTo(WaitOutcome.DENIED);
    }

    @Test
    void given_mixed_run_when_expire_run_then_only_pending_rows_migrated() {
        // 贴近真实形态：同 run 两个等待点，其一在联动快照后被人答复
        AgentWait answered = raisePending("ses_race3", "run_race3", "per_a");
        raisePending("ses_race3", "run_race3", "per_b");
        AgentWaitAppService raced = appServiceInterleavingAfterSnapshot(
                "findByRunIdAndStatus", () -> settleAsDeniedCommitted(answered.getWaitId()));

        int migrated = raced.expireRun("run_race3");

        // 只迁走仍是 PENDING 的行；已答复行保持 SETTLED（deny cap 计数完整）
        assertThat(migrated).isEqualTo(1);
        assertThat(waitRepository.findById(answered.getWaitId()))
                .hasValueSatisfying(w -> {
                    assertThat(w.getStatus()).isEqualTo(WaitStatus.SETTLED);
                    assertThat(w.getSettleOutcome()).isEqualTo(WaitOutcome.DENIED);
                });
        assertThat(waitRepository.findByRunIdAndStatus("run_race3", WaitStatus.EXPIRED))
                .extracting(AgentWait::getEngineRef)
                .containsExactly("per_b");
    }

    @Test
    void given_already_settled_row_when_expire_run_then_noop_returns_zero() {
        // 行为锁定：联动撞上已终态行时静默跳过（守卫迁移的 0 行面）
        AgentWait settled = raisePending("ses_race4", "run_race4", "per_c");
        settled.settle(WaitOutcome.APPROVED, NOW.plusSeconds(30));
        waitRepository.save(settled);

        int migrated = newAgentWaitAppService(guardedRepository(null, null))
                .expireRun("run_race4");

        assertThat(migrated).isZero();
        assertThat(waitRepository.findById(settled.getWaitId()))
                .hasValueSatisfying(w -> {
                    assertThat(w.getStatus()).isEqualTo(WaitStatus.SETTLED);
                    assertThat(w.getSettleOutcome()).isEqualTo(WaitOutcome.APPROVED);
                });
    }

    @Test
    void given_pending_run_when_expire_run_then_migrated_to_expired_without_outcome() {
        // 行为锁定：无竞争时正常联动——PENDING → EXPIRED，settle_outcome 保持 null
        AgentWait pending = raisePending("ses_race5", "run_race5", "per_d");

        int migrated = newAgentWaitAppService(guardedRepository(null, null))
                .expireRun("run_race5");

        assertThat(migrated).isEqualTo(1);
        assertThat(waitRepository.findById(pending.getWaitId()))
                .hasValueSatisfying(w -> {
                    assertThat(w.getStatus()).isEqualTo(WaitStatus.EXPIRED);
                    assertThat(w.getSettleOutcome()).isNull();
                    assertThat(w.getSettledAt()).isEqualTo(NOW); // 服务时钟固定
                });
    }

    // ---------- 构造 ----------

    private AgentWait raisePending(String sessionId, String runId, String engineRef) {
        return waitRepository.save(AgentWait.raise(WORKSPACE_ID, sessionId, runId,
                WaitKind.PERMISSION, engineRef, "执行危险操作", Map.of("id", engineRef),
                NOW.minusSeconds(60)));
    }

    /**
     * settle 写者的落库效果（独立连接、立即提交）：status=SETTLED、
     * settle_outcome=DENIED、settled_at 落时刻——等价「先引擎后落库」契约下
     * settle 的 save 已提交。
     */
    private void settleAsDeniedCommitted(String waitId) {
        jdbcTemplate.update(
                "UPDATE agt_pending_waits SET status = ?, settle_outcome = ?, settled_at = ? "
                        + "WHERE wait_id = ?",
                WaitStatus.SETTLED.getCode(), WaitOutcome.DENIED.getCode(),
                Timestamp.from(NOW.plusSeconds(30)), waitId);
    }

    /** 生产路径的 {@link AgentWaitAppService}，repository 换成带交错钩子的代理。 */
    private AgentWaitAppService appServiceInterleavingAfterSnapshot(String snapshotMethod,
                                                                    Runnable interleave) {
        return newAgentWaitAppService(guardedRepository(snapshotMethod, interleave));
    }

    private AgentWaitAppService newAgentWaitAppService(AgentWaitRepository repository) {
        return new AgentWaitAppService(repository, sessionRepository,
                new WaitResponderDirectory(List.of(), List.of()),
                mock(WorkspaceHandleClient.class), 3, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * 真实仓储的守卫代理：{@code transitionIfStatus} 包显式事务（手动构造的
     * app service 不经 Spring 事务代理，@Modifying 更新需要事务边界；守卫在
     * SQL WHERE 上，事务边界不影响其库层语义）；可选在联动 SELECT 快照返回后
 * 注入交错写者——竞态窗口即「SELECT 已取、写库未发生」。
     */
    private AgentWaitRepository guardedRepository(String snapshotMethod, Runnable interleave) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        AtomicBoolean fired = new AtomicBoolean(false);
        return (AgentWaitRepository) Proxy.newProxyInstance(
                AgentWaitRepository.class.getClassLoader(),
                new Class<?>[] {AgentWaitRepository.class},
                (proxy, method, args) -> {
                    if ("transitionIfStatus".equals(method.getName())) {
                        return tx.execute(status -> invokeReal(method, args));
                    }
                    Object result = invokeReal(method, args);
                    if (snapshotMethod != null && snapshotMethod.equals(method.getName())
                            && fired.compareAndSet(false, true)) {
                        interleave.run(); // SELECT 快照已取、写库未发生——竞态窗口
                    }
                    return result;
                });
    }

    /** 反射解包：目标异常以本来面目抛出（断言失败/数据访问异常不裹反射外壳）。 */
    private Object invokeReal(Method method, Object[] args) {
        try {
            return method.invoke(waitRepository, args);
        } catch (InvocationTargetException | IllegalAccessException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
