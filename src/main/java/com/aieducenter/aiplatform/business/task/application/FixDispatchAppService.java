package com.aieducenter.aiplatform.business.task.application;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.business.project.application.ProjectAgentTaskAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectAgentTaskResponse;
import com.aieducenter.aiplatform.business.task.domain.aggregate.Bug;
import com.aieducenter.aiplatform.business.task.domain.enums.BugStatus;
import com.aieducenter.aiplatform.business.task.domain.error.TaskMessage;
import com.aieducenter.aiplatform.business.task.domain.repository.BugRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 修复编排链（A4 §4，票 #27）：逐 Bug 一 run 一新会话的串行链——同项目同时
 * 至多一个修复 run（同工作区并发互踩文件/git），编排持 sink 收终态（进程内
 * 回调，底座无 run 状态落库——重启恢复见 {@link #recoverOrphanedRuns}）。
 *
 * <p><b>链的推进模型</b>：适配器异步起跑（executor.submit），触发请求
 * （confirm / dispatch-fixes）只付首条 run 的下发成本；终态回调与「派下一条」
 * 在适配器回调线程上推进，一步一查——每次前进步现查可派发池（OPEN ∧
 * fix_run_id IS NULL，旧→新）排除本轮已失败集后取首条。由此：链中新出现的
 * 可派 Bug（如链飞行中复测退回）在下一条 run 收尾后自然接续；触发点落在
 * in-flight 时空转返回，不丢任何 Bug（飞行中 run 的下一步自会续查新池）；
 * 失败的 Bug 只被<b>本轮</b>排除（防失败 run 原地打转），链走完即回池待
 * 下次触发重试。</p>
 *
 * <p><b>派发幂等</b>：in-flight 判定 = 存在 fix_run_id 非空 ∧ OPEN。链前进步
 * 在项目锁内完成「in-flight 判定 + 取下一条 + 落 in-flight 标记」，引擎交互
 * 在锁外（秒到分钟级）——并发触发与链自身推进在此收敛为单链。触发三点
 * （A4 §4）：①首轮确认 ②复测确认有退回 ③dev 手动端点，全部汇入
 * {@link #onConfirmed}/{@link #dispatchFixes(String)}。</p>
 *
 * <p><b>终态处理</b>：正常 finish → Bug 翻 FIXED（乐观翻转，真伪由复测裁决）
 * + 记 fix_run_id/fix_note（run 最终文本消息）→ 派下一条；error/timeout →
 * 留 OPEN + fix_run_id 清 NULL（日志留 runId）回池——失败不阻塞链。经
 * business.project 工作区任务下发端口复用片5 编排（DEV preset、dims role=FIX、
 * 阶段计数/SSE 桥接全继承，期 CLOSED 跳过计数）。</p>
 */
@Service
@Slf4j
public class FixDispatchAppService {

    /** 引擎透传最终文本 part 的 type（开放集合，适配器 emitParts/dsh 同构）。 */
    private static final String TEXT_PART = "text";

    private final BugRepository bugRepository;
    private final ProjectAgentTaskAppService agentTaskAppService;
    private final ProjectQueryAppService projectQueryAppService;
    private final Map<Long, Object> chainLocks = new ConcurrentHashMap<>();

    public FixDispatchAppService(BugRepository bugRepository,
                                 ProjectAgentTaskAppService agentTaskAppService,
                                 ProjectQueryAppService projectQueryAppService) {
        this.bugRepository = bugRepository;
        this.agentTaskAppService = agentTaskAppService;
        this.projectQueryAppService = projectQueryAppService;
    }

    /**
     * 手动（重）派发（dev 端点，A4 §4 触发③）：幂等——已有修复 run 在飞则空转；
     * 无则以空失败集起链。owner 守卫同 dev 动作口径（TASK_009）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；TASK_009 非项目归属账号（403）
     */
    public void dispatchFixes(String projectId) {
        Long parsed = projectQueryAppService.requireProjectId(projectId);
        requireProjectOwner(parsed);
        onConfirmed(parsed);
    }

    /**
     * 确认触发（A4 §4 触发①②，首轮确认入库后 / 复测确认有退回时）：调用点在
     * 确认事务提交后（TaskLifecycleAppService.confirm 的事务块之后）。幂等——
     * in-flight 即空转；无可派 Bug 即空链返回。
     */
    void onConfirmed(Long projectId) {
        advance(projectId, new HashSet<>());
    }

    /**
     * 重启恢复（A4 §4，从简不建 campaign 表）：链是进程内 sink 回调，进程死则
     * 链必已死——启动扫描 fix_run_id 非空 ∧ OPEN 视作孤儿，日志后置 NULL 回
     * 可派发池。重跑一次修复 = 无害冗余（真伪由复测裁决兜底）。
     */
    void recoverOrphanedRuns() {
        bugRepository.findByStatusAndFixRunIdIsNotNull(BugStatus.OPEN).forEach(bug -> {
            String runId = bug.getFixRunId();
            if (bug.abandonFixRun(runId)) {
                bugRepository.save(bug);
                log.warn("[task] 孤儿修复 run 回收：bug {} runId={}（进程重启，链已死，"
                        + "置 NULL 回可派发池）", bug.getId(), runId);
            }
        });
    }

    // ---------- 链内部 ----------

    /**
     * 链前进步（幂等收敛点）：锁内——in-flight 判定 + 现查可派池取下一条
     * （排除本轮失败集）+ 落 in-flight 标记；锁外——引擎下发（同步失败按失败
     * 收口续链，异步终态走 sink 观察者）。无可派 Bug 即链完（失败集随之作废，
     * 其内 Bug 回池待下次触发）。
     */
    private void advance(Long projectId, Set<Long> failedThisRound) {
        Bug next;
        String runId = AgentRunContext.newRunId();
        synchronized (chainLock(projectId)) {
            if (bugRepository.existsByProjectIdAndStatusAndFixRunIdIsNotNull(
                    projectId, BugStatus.OPEN)) {
                return; // 已有修复 run 在飞：并发触发/链已在跑，收敛不重派（其终态自会续查新池）
            }
            next = bugRepository
                    .findByProjectIdAndStatusAndFixRunIdIsNullOrderByCreatedAtAsc(
                            projectId, BugStatus.OPEN)
                    .stream()
                    .filter(bug -> !failedThisRound.contains(bug.getId()))
                    .findFirst()
                    .orElse(null);
            if (next == null) {
                log.info("[task] 修复链走完：project {}（失败回池 {} 条待下次触发）",
                        projectId, failedThisRound.size());
                return;
            }
            next.markFixDispatched(runId);
            bugRepository.save(next);
        }
        log.info("[task] 修复 run 派发：project {} bug {} runId={}", projectId, next.getId(),
                runId);
        dispatchRun(next, runId, failedThisRound);
    }

    /** 引擎下发（经 project 工作区端口，片5 编排全继承）；同步失败即失败收口。 */
    private void dispatchRun(Bug bug, String runId, Set<Long> failedThisRound) {
        try {
            ProjectAgentTaskResponse result = agentTaskAppService.dispatchFixRun(
                    bug.getProjectId(), fixPromptOf(bug), runId,
                    sinkOf(bug, runId, failedThisRound));
            if (!result.accepted()) {
                handleFailure(bug, runId, "引擎未接受修复任务", failedThisRound);
            }
        } catch (RuntimeException e) {
            handleFailure(bug, runId, e.toString(), failedThisRound);
        }
    }

    /** 失败收口（A4 §4）：留 OPEN + 清 fix_run_id，本轮排除该 Bug 后续链。 */
    private void handleFailure(Bug bug, String runId, String reason, Set<Long> failedThisRound) {
        log.warn("[task] 修复 run 失败：bug {} runId={} 原因={}——回池待下次派发，链继续",
                bug.getId(), runId, reason);
        clearRun(bug.getId(), runId);
        failedThisRound.add(bug.getId());
        advance(bug.getProjectId(), failedThisRound);
    }

    /** 链的 sink 观察者：记 run 最终文本为 fix_note 素材，终态驱动翻态/续链。 */
    private Consumer<AgentEvent> sinkOf(Bug bug, String runId, Set<Long> failedThisRound) {
        return new Consumer<>() {
            private String lastText;

            @Override
            public void accept(AgentEvent event) {
                if (TEXT_PART.equals(event.type())) {
                    String text = textOf(event.payload());
                    if (text != null && !text.isBlank()) {
                        lastText = text;
                    }
                } else if (AgentEventTypes.TASK_FINISH.equals(event.type())) {
                    onTerminal(bug.getProjectId(), bug.getId(), runId, true, lastText,
                            failedThisRound);
                } else if (AgentEventTypes.ERROR.equals(event.type())) {
                    onTerminal(bug.getProjectId(), bug.getId(), runId, false, null,
                            failedThisRound);
                }
            }
        };
    }

    /** 终态处理：finish → FIXED 乐观翻转 + fix_note；error/timeout → 清标记回池
     * + 登记失败集（回池但本轮不再重试——防失败 run 原地打转）。 */
    private void onTerminal(Long projectId, Long bugId, String runId, boolean finished,
                            String fixNote, Set<Long> failedThisRound) {
        if (!finished) {
            failedThisRound.add(bugId);
        }
        bugRepository.findById(bugId).ifPresentOrElse(bug -> {
            if (finished) {
                if (bug.markFixed(runId, fixNote)) {
                    bugRepository.save(bug);
                    log.info("[task] 修复 run 完成：bug {} runId={} → FIXED", bugId, runId);
                } else {
                    log.warn("[task] 修复 run {} 终态到达但 bug {} 已离开 OPEN/标记已换，"
                            + "乐观翻转不覆盖", runId, bugId);
                }
            } else if (bug.abandonFixRun(runId)) {
                bugRepository.save(bug);
                log.warn("[task] 修复 run error/timeout：bug {} runId={} → 留 OPEN 回池",
                        bugId, runId);
            }
        }, () -> log.warn("[task] 修复 run {} 终态到达但 bug {} 已不存在", runId, bugId));
        advance(projectId, failedThisRound); // 派下一条（失败不阻塞链）
    }

    /** 清 in-flight 标记（runId 匹配才清——迟到终态不清别人的标记）。 */
    private void clearRun(Long bugId, String runId) {
        bugRepository.findById(bugId)
                .filter(bug -> bug.abandonFixRun(runId))
                .ifPresent(bugRepository::save);
    }

    /** 修复指令 prompt（A4 §4）：Bug 标题/描述/复现步骤 + 修复指令。 */
    private static String fixPromptOf(Bug bug) {
        StringBuilder prompt = new StringBuilder("请修复以下测试 Bug（在项目工作区内修复，"
                + "完成后自查，并在最终回复中说明结论——如已修复/未重现及原因）：\n");
        prompt.append("【标题】").append(bug.getTitle()).append('\n');
        if (bug.getDescription() != null && !bug.getDescription().isBlank()) {
            prompt.append("【描述】").append(bug.getDescription()).append('\n');
        }
        if (bug.getReproSteps() != null && !bug.getReproSteps().isBlank()) {
            prompt.append("【复现步骤】").append(bug.getReproSteps()).append('\n');
        }
        prompt.append("【严重档位】").append(bug.getSeverity().getName());
        return prompt.toString();
    }

    /** 透传 part 的文本载荷（data.text，两引擎同构；无文本返回 null）。 */
    private static String textOf(Map<String, Object> payload) {
        if (payload.get("data") instanceof Map<?, ?> data) {
            return data.get("text") == null ? null : Objects.toString(data.get("text"), null);
        }
        return null;
    }

    private Object chainLock(Long projectId) {
        return chainLocks.computeIfAbsent(projectId, key -> new Object());
    }

    /** dev 动作守卫（与 TaskLifecycleAppService 同口径，A4 §6 视角列）。 */
    private void requireProjectOwner(Long projectId) {
        if (!RequestContext.getUserId()
                .equals(projectQueryAppService.ownerAccountIdOf(projectId))) {
            throw new ApplicationException(TaskMessage.TASK_NOT_OWNER);
        }
    }
}
