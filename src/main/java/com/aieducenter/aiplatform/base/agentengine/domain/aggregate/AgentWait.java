package com.aieducenter.aiplatform.base.agentengine.domain.aggregate;

import java.time.Instant;
import java.util.Map;

import cn.hutool.core.util.StrUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;

/**
 * 等待点聚合根（{@code agt_pending_waits}，A1 §1.1 口子①统一模型）：agent 运行中
 * 挂起等人反馈的底座实体——问答（QUESTION）与权限（PERMISSION）统一承载。
 *
 * <p>{@code waitId} 是平台生成的稳定标识（TSID 十进制字符串，主键即引用键）：
 * 跨重启存活，业务层以 waitId 不透明引用（口子③转任务的回填键），底座不解释。
 * 中性寻址：按 workspaceId 聚合，无 projectId。{@code body} 为引擎载荷原样
 * （jsonb 透传存储）；{@code engineRef} 是引擎侧请求/权限 id——settle 时答复
 * 派发的寻址键。raise 幂等 = 同 (session_id, engine_ref) 至多一行 PENDING
 * （库层部分唯一索引兜底；终态行保留为历史，不挡引擎侧同挂起的再登记）。</p>
 *
 * <p>生命周期（单向，只能从 PENDING 迁出一次）：{@link #settle}（人已答复，
 * 结果落 settle_outcome）/ {@link #expire}（run 终态联动，A1 §1.3）/
 * {@link #cancel}（复用会话下发前清理残留）。不软删除：终态行即历史。不变量
 * 由持久层守卫 UPDATE 强制（票 #37）：联动/清理迁移带 {@code WHERE status=PENDING}
 * 条件（{@code AgentWaitRepository#transitionIfStatus}），与 settle 落库交错时
 * 后写不得胜出——本类的 {@code requirePending} 只是内存前置，不是竞争防线。</p>
 */
@Entity
@Table(name = "agt_pending_waits")
@Aggregate
@Getter
public class AgentWait extends Auditable implements AggregateRoot<AgentWait, String> {

    @Id
    @Column(name = "wait_id", nullable = false, updatable = false, length = 50)
    private String waitId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    /** 引擎会话标识原样（与 agt_agent_sessions.session_id 同值）。 */
    @Column(name = "session_id", nullable = false, updatable = false, length = 100)
    private String sessionId;

    /** 所属一次运行（deny 计数与终态联动的锚）。 */
    @Column(name = "run_id", nullable = false, updatable = false, length = 100)
    private String runId;

    /** 引擎侧请求/权限 id（que_* / permission id；答复派发键）。 */
    @Column(name = "engine_ref", nullable = false, updatable = false, length = 100)
    private String engineRef;

    @Column(name = "kind", nullable = false, updatable = false)
    private WaitKind kind;

    @Column(name = "status", nullable = false)
    private WaitStatus status;

    /** 引擎载荷原样（底座不解释；null 归一为 NULL 列）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> body;

    /** 适配器从引擎载荷提取的中性短文本（SSE wait-raised 的 summary 同源）。 */
    @Column(name = "summary", updatable = false, length = 500)
    private String summary;

    /** settle 结果（仅 status=SETTLED 时有值）。 */
    @Column(name = "settle_outcome")
    private WaitOutcome settleOutcome;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private Instant raisedAt;

    /** 关闭时刻（settle/expire/cancel 三途同记）。 */
    @Column(name = "settled_at")
    private Instant settledAt;

    protected AgentWait() {
    }

    private AgentWait(long workspaceId, String sessionId, String runId, WaitKind kind,
                      String engineRef, String summary, Map<String, Object> body,
                      Instant raisedAt) {
        if (workspaceId <= 0 || StrUtil.isBlank(sessionId) || StrUtil.isBlank(runId)
                || kind == null || StrUtil.isBlank(engineRef) || raisedAt == null) {
            throw new DomainException(AgentEngineMessage.WAIT_FIELDS_INCOMPLETE);
        }
        this.waitId = Long.toString(TsidGenerator.newInstance().generate());
        this.workspaceId = workspaceId;
        this.sessionId = sessionId;
        this.runId = runId;
        this.kind = kind;
        this.engineRef = engineRef;
        this.summary = StrUtil.blankToDefault(summary, null);
        this.body = body == null || body.isEmpty() ? null : body;
        this.raisedAt = raisedAt;
        this.status = WaitStatus.PENDING;
    }

    /**
     * 登记新等待点（发现通道检出即 raise；waitId 此刻生成，此后不变）。
     */
    public static AgentWait raise(long workspaceId, String sessionId, String runId,
                                  WaitKind kind, String engineRef, String summary,
                                  Map<String, Object> body, Instant raisedAt) {
        return new AgentWait(workspaceId, sessionId, runId, kind, engineRef, summary,
                body, raisedAt);
    }

    /**
     * 人已答复关闭（answer/approve/deny/deferred 四果之一）。非 PENDING 即非法跳变。
     */
    public void settle(WaitOutcome outcome, Instant settledAt) {
        requirePending();
        this.settleOutcome = outcome;
        close(WaitStatus.SETTLED, settledAt);
    }

    /**
     * run 终态联动关闭（finish/error/timeout/cancel → 其 PENDING 等待点 → EXPIRED）。
     */
    public void expire(Instant settledAt) {
        requirePending();
        close(WaitStatus.EXPIRED, settledAt);
    }

    /**
     * 复用会话下发前的残留清理（「有则先清理再跑」，A1 §1.3）。
     */
    public void cancel(Instant settledAt) {
        requirePending();
        close(WaitStatus.CANCELLED, settledAt);
    }

    private void requirePending() {
        if (status != WaitStatus.PENDING) {
            throw new DomainException(AgentEngineMessage.WAIT_CONFLICT);
        }
    }

    private void close(WaitStatus target, Instant settledAt) {
        if (settledAt == null) {
            throw new DomainException(AgentEngineMessage.WAIT_FIELDS_INCOMPLETE);
        }
        this.status = target;
        this.settledAt = settledAt;
    }

    /**
     * 聚合 ID = waitId（稳定标识即主键）。
     */
    @Override
    public String getId() {
        return waitId;
    }
}
