package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 续跑闸（#48）：settle 续跑任务的串行执行与会话级终止口径。
 *
 * <ul>
 *   <li><b>串行</b>：单线程执行器——同会话的续跑（一 run 多 approve 点逐个 settle）
 *       与多会话续跑都排队执行，不并发写同一 AgentState 槽位。</li>
 *   <li><b>终止</b>：{@link #close}（deny cap 平台终止路径）= 取消该会话在飞/排队的
 *       续跑 + 关闸（后续提交被拒）——settle 先派发后判 cap 的时序里，排队中的
 *       deny-续跑被取消，不再把「拒绝」喂回引擎诱发新一轮重试挂起。</li>
 *   <li><b>复活</b>：{@link #reopen}——同会话承接新 run（用户开新一轮对话）即开闸，
 *       终止只作用于 run 生命周期，不污染会话。</li>
 * </ul>
 *
 * <p>口径与平台既有孤儿容忍一致：闸是进程内状态，重启即清（重启后等待点已终态
 * 收口，不会有再派发）。</p>
 */
@Component
@Slf4j
public class ChatAgentResumeGate implements DisposableBean {

    private final ExecutorService ownedExecutor;
    /** 提交通道（生产=线程池异步；测试=直通同步）——统一返回 Future 供取消。 */
    private final Function<Runnable, Future<?>> submitter;
    private final Map<String, Future<?>> inFlight = new ConcurrentHashMap<>();
    private final Set<String> closed = ConcurrentHashMap.newKeySet();

    public ChatAgentResumeGate() {
        this.ownedExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chatagent-resume");
            thread.setDaemon(true);
            return thread;
        });
        this.submitter = this.ownedExecutor::submit;
    }

    /** 测试便利构造（直通执行器，无生命周期；public 供跨包测试装配，如 AppService 单测）。 */
    public ChatAgentResumeGate(java.util.concurrent.Executor executor) {
        this.ownedExecutor = null;
        this.submitter = task -> {
            executor.execute(task);
            return CompletableFuture.completedFuture(null);
        };
    }

    /** 提交续跑任务；会话已关闸返回 {@code false}（不执行）。 */
    public boolean submit(String sessionId, Runnable task) {
        if (closed.contains(sessionId)) {
            log.warn("[chatagent] 会话续跑已关闸，丢弃提交：session={}", sessionId);
            return false;
        }
        inFlight.put(sessionId, submitter.apply(() -> {
            try {
                task.run();
            }
            catch (RuntimeException e) {
                log.warn("[chatagent] 续跑任务异常：session={}, {}", sessionId, e.getMessage());
            }
            finally {
                inFlight.remove(sessionId);
            }
        }));
        return true;
    }

    /** 关闸（deny cap 终止）：取消在飞续跑 + 拒绝后续提交。 */
    public void close(String sessionId) {
        closed.add(sessionId);
        Future<?> future = inFlight.remove(sessionId);
        if (future != null) {
            future.cancel(true);
        }
    }

    /** 开闸：会话承接新 run（新对话轮）即复活。 */
    public void reopen(String sessionId) {
        closed.remove(sessionId);
    }

    @Override
    public void destroy() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }
}
