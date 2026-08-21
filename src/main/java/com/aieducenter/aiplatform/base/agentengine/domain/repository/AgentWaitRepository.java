package com.aieducenter.aiplatform.base.agentengine.domain.repository;

import java.util.List;
import java.util.Optional;

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

    /** raise 幂等寻址：同 (session_id, engine_ref) 的 PENDING 行（无则可登记）。 */
    Optional<AgentWait> findBySessionIdAndEngineRefAndStatus(String sessionId,
                                                             String engineRef,
                                                             WaitStatus status);

    /** 工作区某状态的等待点（PENDING 即跨会话待处理聚合面），新者在前。 */
    List<AgentWait> findByWorkspaceIdAndStatusOrderByRaisedAtDesc(Long workspaceId,
                                                                  WaitStatus status);

    /** 某状态的全部等待点（跨项目待办查询面：一次取全量按工作区分组，A2 §60）。 */
    List<AgentWait> findByStatus(WaitStatus status);

    /** 一次运行名下的等待点（终态联动 / deny 计数的锚）。 */
    List<AgentWait> findByRunIdAndStatus(String runId, WaitStatus status);

    /** 一个会话名下的等待点（复用会话下发前的残留清理面）。 */
    List<AgentWait> findBySessionIdAndStatus(String sessionId, WaitStatus status);

    /** 同 run 内某结果的计数（permission deny cap，A1 §1.3）。 */
    long countByRunIdAndStatusAndSettleOutcome(String runId, WaitStatus status,
                                               WaitOutcome settleOutcome);
}
