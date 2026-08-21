package com.aieducenter.aiplatform.base.agentengine.domain.aggregate;

import cn.hutool.core.util.StrUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;

/**
 * agent 会话聚合根（{@code agt_agent_sessions}）：一次引擎会话的落库记录，
 * 按 workspaceId 寻址、跨重启存活（B0 蓝图 §2 片2）。
 *
 * <p>会话是跨运行的持久寻址（CONTEXT.md：与 runId「一次运行」并存不混淆）——
 * runTask 建会话即登记（accepted），复用 sessionId 续跑即 {@link #ranOn(String)}
 * 刷新最近运行。{@code sessionId} 为引擎侧标识原样（opencode {@code ses_*} /
 * dsh 适配器自生成 {@code dsh-*}），引擎 + sessionId 全局唯一。不软删除
 * （Auditable 只取审计字段），与运行记录同生共死由持有方管理。</p>
 */
@Entity
@Table(name = "agt_agent_sessions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"engine", "session_id"})
})
@Aggregate
@Getter
public class AgentSession extends Auditable implements AggregateRoot<AgentSession, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 引擎会话标识原样（引擎侧寻址键）。 */
    @Column(name = "session_id", nullable = false, updatable = false, length = 100)
    private String sessionId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @Column(name = "engine", nullable = false, updatable = false, length = 50)
    private String engine;

    /** 最近一次运行的 runId（续跑刷新）。 */
    @Column(name = "last_run_id", nullable = false, length = 100)
    private String lastRunId;

    protected AgentSession() {
    }

    private AgentSession(long workspaceId, String engine, String sessionId, String runId) {
        if (workspaceId <= 0 || StrUtil.isBlank(engine) || StrUtil.isBlank(sessionId)
                || StrUtil.isBlank(runId)) {
            throw new DomainException(AgentEngineMessage.SESSION_FIELDS_INCOMPLETE);
        }
        this.workspaceId = workspaceId;
        this.engine = engine;
        this.sessionId = sessionId;
        this.lastRunId = runId;
    }

    /**
     * 登记新会话（runTask 建会话成功后调用）。
     */
    public static AgentSession open(long workspaceId, String engine, String sessionId,
                                    String runId) {
        return new AgentSession(workspaceId, engine, sessionId, runId);
    }

    /**
     * 续跑刷新：本会话承接了新一次运行。
     */
    public void ranOn(String runId) {
        if (StrUtil.isBlank(runId)) {
            throw new DomainException(AgentEngineMessage.SESSION_FIELDS_INCOMPLETE);
        }
        this.lastRunId = runId;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
