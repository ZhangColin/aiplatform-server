package com.aieducenter.aiplatform.base.agentengine.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.BaseCodeMessage;

import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.SettleResult;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentWait;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentSession;
import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.domain.model.WaitSettlement;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentSessionRepository;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentWaitRepository;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;

import lombok.extern.slf4j.Slf4j;

/**
 * 等待点用例（片2b，A1 §1 口子①统一模型）：问答/权限两类挂起的登记、跨会话聚合、
 * 三型答复（Answer 选项 label / PermissionDecision / Deferred 转任务关闭）。
 *
 * <p>CC 避雷清单落点（A1 §1.3）：settle 前校验 status=PENDING 且会话可续跑，否则
 * AGT_007（409，陈旧批准非法跳变防护）；同 run 内 permission deny 计数 ≥ deny cap
 * （可配 {@code app.agent.wait-deny-cap}，默认 3）→ 平台终止——<b>判定</b>在
 * {@link SettleResult} 回报，<b>终止执行</b>归调用方经
 * {@link AgentTaskAppService#terminateRun}（票 #38 与 cancelRun 共用：abort + 收口 +
 * 平台终态帧），防拒绝后换形式重试的审批循环；run 终态联动（{@link #expireRun}）与
 * 复用会话残留清理（{@link #cancelSessionWaits}）由任务下发的流桥接线
 * （{@link AgentTaskAppService}）。</p>
 *
 * <p>答复顺序：先引擎后落库——引擎交互失败抛 AGT_004 且等待点保持 PENDING
 * （可重试）；落库只记成功送达引擎的答复。Deferred 例外（转任务 = 纯平台侧关闭，
 * 不派发引擎）。引擎交互不进事务（秒到分钟级），落库靠仓储单行事务。</p>
 */
@Service
@Slf4j
public class AgentWaitAppService {

    private final AgentWaitRepository waitRepository;
    private final AgentSessionRepository sessionRepository;
    private final WaitResponderDirectory responders;
    private final WorkspaceHandleClient workspaceHandleClient;
    private final int denyCap;
    private final Clock clock;

    @Autowired
    public AgentWaitAppService(AgentWaitRepository waitRepository,
                               AgentSessionRepository sessionRepository,
                               WaitResponderDirectory responders,
                               WorkspaceHandleClient workspaceHandleClient,
                               @Value("${app.agent.wait-deny-cap:3}") int denyCap) {
        this(waitRepository, sessionRepository, responders, workspaceHandleClient,
                denyCap, Clock.systemUTC());
    }

    public AgentWaitAppService(AgentWaitRepository waitRepository,
                               AgentSessionRepository sessionRepository,
                               WaitResponderDirectory responders,
                               WorkspaceHandleClient workspaceHandleClient,
                               int denyCap, Clock clock) {
        this.waitRepository = waitRepository;
        this.sessionRepository = sessionRepository;
        this.responders = responders;
        this.workspaceHandleClient = workspaceHandleClient;
        this.denyCap = denyCap;
        this.clock = clock;
    }

    /**
     * 登记等待点（发现通道检出即调用）：同 (sessionId, engineRef) 已有 <b>PENDING</b>
     * 行则幂等返回（发现通道重复上报/轮询重叠的收敛点）；终态行不挡路——引擎侧
     * 挂起若真还活着（如超时联动误伤后新 run 重检到），登记为新 PENDING 行（新
     * waitId/新 run——「重启后看得见答不了」的 demo 病对策）。并发同挂起双登记由
     * PENDING 部分唯一索引兜底。
     */
    public WaitPointResponse raise(long workspaceId, String sessionId, String runId,
                                   WaitKind kind, String engineRef, String summary,
                                   Map<String, Object> body) {
        AgentWait wait = pendingByRef(sessionId, engineRef).orElseGet(() -> {
            try {
                return waitRepository.save(AgentWait.raise(
                        workspaceId, sessionId, runId, kind, engineRef, summary, body,
                        clock.instant()));
            } catch (DataIntegrityViolationException e) {
                // 并发双登记：部分唯一索引拒后者，回读胜者
                return pendingByRef(sessionId, engineRef).orElseThrow(() -> e);
            }
        });
        log.info("[agentengine] 等待点登记 waitId={} kind={} session={} run={}",
                wait.getWaitId(), kind, sessionId, runId);
        return WaitPointResponse.from(wait);
    }

    /**
     * 从 wait-raised 流事件载荷登记（sink 桥接的入口）：payload 契约键见
     * {@link AgentEventTypes} WAIT_* 常量（适配器已归一，引擎差异不外露）。
     */
    public WaitPointResponse raiseFromEvent(long workspaceId, Map<String, Object> payload) {
        return raise(workspaceId,
                text(payload, AgentEventTypes.WAIT_SESSION_FIELD),
                text(payload, AgentEventTypes.WAIT_RUN_FIELD),
                WaitKind.valueOf(text(payload, AgentEventTypes.WAIT_KIND_FIELD)),
                text(payload, AgentEventTypes.WAIT_ENGINE_REF_FIELD),
                text(payload, AgentEventTypes.WAIT_SUMMARY_FIELD),
                castBody(payload.get(AgentEventTypes.WAIT_DATA_FIELD)));
    }

    /**
     * 工作区待处理等待点（跨会话聚合，新者在前）。工作区不存在由 workspace 侧抛
     * WSP_001（404）。纯表读：发现通道（适配器 watcher）负责登记，本口不做引擎轮询。
     */
    @Transactional(readOnly = true)
    public List<WaitPointResponse> pendingWaits(String workspaceId) {
        return waitRepository
                .findByWorkspaceIdAndStatusOrderByRaisedAtDesc(
                        resolveWorkspaceId(workspaceId), WaitStatus.PENDING)
                .stream().map(WaitPointResponse::from).toList();
    }

    /**
     * 单查等待点（waitId 全局寻址——业务层回填引用的读面，不限工作区）。
     */
    @Transactional(readOnly = true)
    public Optional<WaitPointResponse> wait(String waitId) {
        return waitRepository.findById(waitId).map(WaitPointResponse::from);
    }

    /**
     * 有待处理等待点的工作区 id 集（跨项目待办查询面，A2 §60：工作台 GATE/
     * WAIT 派生与项目列表 pending 过滤共用；纯表读一次取全量，量小不分页）。
     */
    @Transactional(readOnly = true)
    public Set<Long> pendingWorkspaceIds() {
        return waitRepository.findByStatusOrderByRaisedAtDesc(WaitStatus.PENDING).stream()
                .map(AgentWait::getWorkspaceId)
                .collect(Collectors.toSet());
    }

    /**
     * 跨项目全部待处理等待点（工作台 AGENT_WAIT 待办投影源，A2 §4/§5）：一次取
     * 全量、新者在前，量小不分页。纯表读——本口不做引擎轮询、不解释 body；
     * 待办的 title 等呈现归 workbench 投影层。
     */
    @Transactional(readOnly = true)
    public List<WaitPointResponse> listPendingWaits() {
        return waitRepository.findByStatusOrderByRaisedAtDesc(WaitStatus.PENDING)
                .stream().map(WaitPointResponse::from).toList();
    }

    /**
     * 答复等待点（REST 命令形态）：按 type 映射三型后走 {@link #settle(String, WaitSettlement)}。
     * 型内必填缺失抛 ApplicationException（BaseCodeMessage.BAD_REQUEST，全局异常
     * 处理的 400 面——IllegalArgumentException 无映射会落 500）。
     */
    public SettleResult settle(String workspaceId, String waitId, WaitSettleCommand command) {
        return settle(workspaceId, switch (command.type()) {
            case WaitSettleCommand.TYPE_ANSWER -> {
                if (command.answers() == null || command.answers().isEmpty()) {
                    throw new ApplicationException(BaseCodeMessage.BAD_REQUEST,
                            "type=answer 必填 answers");
                }
                yield new WaitSettlement.Answer(waitId, command.answers());
            }
            case WaitSettleCommand.TYPE_PERMISSION -> {
                if (command.approve() == null) {
                    throw new ApplicationException(BaseCodeMessage.BAD_REQUEST,
                            "type=permission 必填 approve");
                }
                yield new WaitSettlement.PermissionDecision(waitId, command.approve());
            }
            case WaitSettleCommand.TYPE_DEFERRED -> new WaitSettlement.Deferred(
                    waitId, command.note());
            default -> throw new ApplicationException(BaseCodeMessage.BAD_REQUEST,
                    "type 取值必须是 answer / permission / deferred: " + command.type());
        });
    }

    /**
     * 答复等待点：三型封闭（Answer/PermissionDecision/Deferred）。校验链——
     * 存在（AGT_006 404）→ PENDING（AGT_007 409）→ 会话可续跑（409）→ 引擎送达
     * （失败 AGT_004 且保持 PENDING 可重试）→ 落库关闭。deny 达 cap 的<b>判定</b>在
     * 结果上回报（{@link SettleResult#denyCapped()}）——终止执行（abort + 收口 +
     * 平台终态帧）归调用方经 {@link AgentTaskAppService#terminateRun} 走与 cancelRun
     * 共用的路径，保证帧序 wait-settled 先于 task-finish（票 #38）。
     */
    public SettleResult settle(String workspaceId, WaitSettlement settlement) {
        AgentWait wait = requireSettleable(workspaceId, settlement.waitId());
        AgentSession session = requireResumableSession(wait);
        WorkspaceHandle handle =
                workspaceHandleClient.handleOf(Long.toString(wait.getWorkspaceId()));
        Instant now = clock.instant();

        switch (settlement) {
            case WaitSettlement.Answer answer -> {
                replyAnswers(handle, wait, session, answer);
                wait.settle(WaitOutcome.ANSWERED, now);
            }
            case WaitSettlement.PermissionDecision decision -> {
                replyPermission(handle, wait, session, decision);
                wait.settle(decision.approve() ? WaitOutcome.APPROVED : WaitOutcome.DENIED,
                        now);
            }
            case WaitSettlement.Deferred deferred -> {
                // 转任务关闭（口子③）：不派发引擎——续跑是业务层建任务后的新消息，
                // 不是问答答复（demo replyQuestions 装不下任务结果，A1 §3.1）
                log.info("[agentengine] 等待点转任务关闭 waitId={} note={}",
                        wait.getWaitId(), deferred.note());
                wait.settle(WaitOutcome.DEFERRED, now);
            }
        }
        waitRepository.save(wait);

        boolean denyCapped = settlement instanceof WaitSettlement.PermissionDecision decision
                && !decision.approve() && denyCapReached(wait.getRunId());
        return new SettleResult(WaitPointResponse.from(wait), session.getEngine(), denyCapped);
    }

    /**
     * run 终态联动（finish/error/timeout/cancel）：其 PENDING 等待点全部 EXPIRED
     * （「工具超时/崩溃留 ASKING 死状态」对策）。守卫迁移（票 #37）：与 settle 落库
     * 交错时行已被迁出即跳过——返回<b>实际联动行数</b>（0 = 无事发生）。
     */
    @Transactional
    public int expireRun(String runId) {
        return expireRunReturning(runId).size();
    }

    /**
     * run 终止联动收口并回报收口行（票 #38：cancelRun / deny cap 平台终止共用）——
     * PENDING 守卫迁移 EXPIRED 后返回<b>实际迁移行</b>投影（wait-settled 帧发射用，
     * 已被 settle 的行不混入）。空表 = 无事发生。
     */
    @Transactional
    public List<WaitPointResponse> expireRunReturning(String runId) {
        return closeAll(waitRepository.findByRunIdAndStatus(runId, WaitStatus.PENDING),
                WaitStatus.EXPIRED, "run 终止联动").stream().map(WaitPointResponse::from)
                .toList();
    }

    /**
     * 复用会话下发前的残留清理（「有则先清理再跑」）：会话名下 PENDING 全部
     * CANCELLED。守卫迁移（票 #37）：返回<b>实际清理行数</b>（被别途迁出的行跳过）。
     */
    @Transactional
    public int cancelSessionWaits(String sessionId) {
        return closeAll(waitRepository.findBySessionIdAndStatus(sessionId, WaitStatus.PENDING),
                WaitStatus.CANCELLED, "复用会话清理").size();
    }

    // ---------- 内部 ----------

    /**
     * deny cap 判定（A1 §1.3 审批循环对策）：同 run 内 deny 累计 ≥ 阈值。只判不定——
     * 终止执行（abort + 收口 + 平台终态帧）经 {@link AgentTaskAppService#terminateRun}
     * 归调用方（票 #38 与 cancelRun 统一路径）。
     */
    private boolean denyCapReached(String runId) {
        long denies = waitRepository.countByRunIdAndStatusAndSettleOutcome(
                runId, WaitStatus.SETTLED, WaitOutcome.DENIED);
        if (denies < denyCap) {
            return false;
        }
        log.warn("[agentengine] run {} 内权限拒绝累计 {} 次达上限（deny cap={}），平台终止运行",
                runId, denies, denyCap);
        return true;
    }

    private void replyAnswers(WorkspaceHandle handle, AgentWait wait, AgentSession session,
                              WaitSettlement.Answer answer) {
        try {
            responders.require(session.getEngine())
                    .replyQuestions(handle, wait.getSessionId(), wait.getEngineRef(),
                            answer.answers());
        } catch (RuntimeException e) {
            throw engineRequestFailed(e);
        }
    }

    private void replyPermission(WorkspaceHandle handle, AgentWait wait, AgentSession session,
                                 WaitSettlement.PermissionDecision decision) {
        try {
            responders.require(session.getEngine())
                    .replyPermission(handle, wait.getSessionId(), wait.getEngineRef(),
                            decision.approve());
        } catch (RuntimeException e) {
            throw engineRequestFailed(e);
        }
    }

    /** settle 前置校验：存在（404）→ 属于该工作区且 PENDING（409）。 */
    private AgentWait requireSettleable(String workspaceId, String waitId) {
        AgentWait wait = waitRepository.findById(waitId)
                .orElseThrow(() -> new ApplicationException(AgentEngineMessage.WAIT_NOT_FOUND));
        if (wait.getWorkspaceId() != resolveWorkspaceId(workspaceId)
                || wait.getStatus() != WaitStatus.PENDING) {
            throw waitConflict();
        }
        return wait;
    }

    /** 会话可续跑校验（409）：会话行存在且属于等待点的工作区（引擎自会话行自述）。 */
    private AgentSession requireResumableSession(AgentWait wait) {
        AgentSession session = sessionRepository.findBySessionId(wait.getSessionId())
                .orElseThrow(AgentWaitAppService::waitConflict);
        if (session.getWorkspaceId() != wait.getWorkspaceId()) {
            throw waitConflict();
        }
        return session;
    }

    /** AGT_007（409）：陈旧答复/不可续跑（A1 §1.3「陈旧批准非法跳变」防护）。 */
    private static ApplicationException waitConflict() {
        return new ApplicationException(AgentEngineMessage.WAIT_CONFLICT);
    }

    private long resolveWorkspaceId(String workspaceId) {
        return workspaceHandleClient.handleOf(workspaceId).workspaceId().id();
    }

    private Optional<AgentWait> pendingByRef(String sessionId, String engineRef) {
        return waitRepository.findBySessionIdAndEngineRefAndStatus(
                sessionId, engineRef, WaitStatus.PENDING);
    }

    /**
     * 守卫迁移快照行（票 #37 竞态根治）：候选来自联动前的 PENDING 快照，逐行经
     * {@link AgentWaitRepository#transitionIfStatus}（守卫在 SQL WHERE 上——
     * 「只能从 PENDING 迁出一次」由库层强制，不再依赖内存态判断）。命中 0 =
     * 行已被 settle 等别途迁出，静默跳过——后写不得胜出。命中行在内存实体上呈现
     * 终态（{@link AgentWait#expire}/{@link AgentWait#cancel} 行为方法保留，供
     * 返回投影呈现；持久化已由守卫 UPDATE 完成，不经实体 save）。
     */
    private List<AgentWait> closeAll(List<AgentWait> snapshot, WaitStatus target,
                                     String cause) {
        Instant now = clock.instant();
        List<AgentWait> migrated = new ArrayList<>(snapshot.size());
        for (AgentWait wait : snapshot) {
            if (waitRepository.transitionIfStatus(wait.getWaitId(), WaitStatus.PENDING,
                    target, now) == 0) {
                continue;
            }
            if (target == WaitStatus.EXPIRED) {
                wait.expire(now);
            } else {
                wait.cancel(now);
            }
            migrated.add(wait);
        }
        if (!migrated.isEmpty()) {
            log.info("[agentengine] {}：{} 行等待点 → {}（首行 run={}）", cause, migrated.size(),
                    target, migrated.get(0).getRunId());
        }
        return migrated;
    }

    private ApplicationException engineRequestFailed(RuntimeException e) {
        log.warn("[agentengine] 等待点答复引擎交互失败：{}", e.getMessage());
        return new ApplicationException(AgentEngineMessage.ENGINE_REQUEST_FAILED, e.getMessage());
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("wait-raised 事件 payload 缺字段 " + key);
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castBody(Object data) {
        return data instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
