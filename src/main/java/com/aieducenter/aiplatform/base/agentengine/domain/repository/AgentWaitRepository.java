package com.aieducenter.aiplatform.base.agentengine.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentWait;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;

/**
 * 等待点仓储：按 workspaceId 跨会话聚合（工作区待处理入口）、按 runId 终态联动、
 * 按 (sessionId, engineRef) + PENDING 做 raise 幂等寻址（终态行不挡再登记——
 * 引擎侧挂起若真还活着，新 run 检出到即新行；PENDING 唯一性由库层部分索引兜底）。
 */
public interface AgentWaitRepository extends BaseRepository<AgentWait, String> {

    /**
     * 守卫迁移（票 #37 竞态根治）：仅当行仍处 {@code guard} 状态时迁为 {@code target}
     * （守卫在 SQL WHERE 上，库层强制「只能从 PENDING 迁出一次」——联动/清理与
     * settle 落库交错时后写不得胜出）。返回实际迁移行数（0 = 已被别途迁出，
     * 静默跳过；非异常）。只写 status 与 settled_at；settle_outcome 归 settle 路径。
     */
    @Modifying
    @Query("""
            update AgentWait w
            set w.status = :target, w.settledAt = :settledAt
            where w.waitId = :waitId and w.status = :guard
            """)
    int transitionIfStatus(String waitId, WaitStatus guard, WaitStatus target,
                           Instant settledAt);

    /** raise 幂等寻址：同 (session_id, engine_ref) 的 PENDING 行（无则可登记）。 */
    Optional<AgentWait> findBySessionIdAndEngineRefAndStatus(String sessionId,
                                                             String engineRef,
                                                             WaitStatus status);

    /** 工作区某状态的等待点（PENDING 即跨会话待处理聚合面），新者在前。 */
    List<AgentWait> findByWorkspaceIdAndStatusOrderByRaisedAtDesc(Long workspaceId,
                                                                  WaitStatus status);

    /** 某状态的全部等待点（跨项目待办查询面：工作台 AGENT_WAIT 投影与项目列表
     * pending 过滤共用，新者在前，A2 §60）。 */
    List<AgentWait> findByStatusOrderByRaisedAtDesc(WaitStatus status);

    /** 一次运行名下的等待点（终态联动 / deny 计数的锚）。 */
    List<AgentWait> findByRunIdAndStatus(String runId, WaitStatus status);

    /** 一次运行名下的全部等待点（运行终止的 runId 解析锚，票 #38——行自带 sessionId）。 */
    List<AgentWait> findByRunId(String runId);

    /** 一个会话名下的等待点（复用会话下发前的残留清理面）。 */
    List<AgentWait> findBySessionIdAndStatus(String sessionId, WaitStatus status);

    /** 同 run 内某结果的计数（permission deny cap，A1 §1.3）。 */
    long countByRunIdAndStatusAndSettleOutcome(String runId, WaitStatus status,
                                               WaitOutcome settleOutcome);
}
