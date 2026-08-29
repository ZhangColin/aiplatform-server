package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 续跑闸（#48 / #59）：settle 续跑任务的会话级串行、跨会话并行与终止口径。
 *
 * <ul>
 *   <li><b>会话级串行</b>：sessionId 哈希固定落一个 stripe（单线程 FIFO）——同会话
 *       的续跑（一 run 多 approve 点逐个 settle）严格排队执行，不并发写同一
 *       AgentState 槽位。</li>
 *   <li><b>跨会话并行</b>（#59）：不同会话多数落不同 stripe 并行执行（单线程全局
 *       排队曾是「创建→对话」分钟级等待的第二来源）；哈希碰撞时退化为同 stripe
 *       排队——不劣于改造前。并行度上界 = stripe 数（{@value #STRIPES}，daemon）。</li>
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

    /** stripe 数 = 常驻线程数 = 跨会话并行度上界（#59）。 */
    static final int STRIPES = 8;

    private final ExecutorService[] stripes;
    /** 提交通道（生产=null 走 stripe 池；测试=直通同步）。 */
    private final Executor passthrough;
    private final Map<String, Future<?>> inFlight = new ConcurrentHashMap<>();
    private final Set<String> closed = ConcurrentHashMap.newKeySet();

    public ChatAgentResumeGate() {
        this.stripes = new ExecutorService[STRIPES];
        for (int index = 0; index < STRIPES; index++) {
            final int stripeIndex = index;
            this.stripes[index] = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "chatagent-resume-" + stripeIndex);
                thread.setDaemon(true);
                return thread;
            });
        }
        this.passthrough = null;
    }

    /** 测试便利构造（直通执行器，无生命周期；public 供跨包测试装配，如 AppService 单测）。 */
    public ChatAgentResumeGate(Executor executor) {
        this.stripes = null;
        this.passthrough = executor;
    }

    /** 提交续跑任务；会话已关闸返回 {@code false}（不执行）。 */
    public boolean submit(String sessionId, Runnable task) {
        if (closed.contains(sessionId)) {
            log.warn("[chatagent] 会话续跑已关闸，丢弃提交：session={}", sessionId);
            return false;
        }
        inFlight.put(sessionId, dispatch(sessionId, () -> {
            try {
                // 起跑复核：前一个任务完成会移走本任务的在飞登记，close 随后落空——
                // 排队任务是否执行以起跑时的闸状态为准（提交时过关 ≠ 起跑时过关）
                if (closed.contains(sessionId)) {
                    log.warn("[chatagent] 会话续跑已关闸，丢弃排队任务：session={}", sessionId);
                    return;
                }
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

    /** 生产：落 sessionId 哈希对应的 stripe（同会话恒同 stripe → 串行）；测试：直通。 */
    private Future<?> dispatch(String sessionId, Runnable wrapped) {
        if (passthrough != null) {
            passthrough.execute(wrapped);
            return CompletableFuture.completedFuture(null);
        }
        return stripes[stripeIndex(sessionId)].submit(wrapped);
    }

    /** stripe 路由（package-private 供测试挑不同 stripe 的会话对，勿在实现外复刻）。 */
    static int stripeIndex(String sessionId) {
        return Math.floorMod(sessionId.hashCode(), STRIPES);
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
        if (stripes != null) {
            for (ExecutorService stripe : stripes) {
                stripe.shutdownNow();
            }
        }
    }
}
