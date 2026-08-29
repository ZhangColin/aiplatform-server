package com.aieducenter.aiplatform.base.workspace.application;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 工作区后台置备器（#58/#61 异步化核心）：创建即返回（记录 PROVISIONING 落库），
 * docker 置备转后台并行——有界队列 + 固定线程池驱动 {@link EnvironmentBackend}，
 * 成功经 {@code complete} 回填端口 + 资源转 READY，失败级联回滚（#57 口径，后端内部
 * 已回滚物理资源）+ 自诊断（WSP_008 等，后端归一化）后转 FAILED（带失败原因，#63）。
 *
 * <p>失败重试（#63）：单条置备最多尝试 {@code maxAttempts} 次（可配
 * {@code app.workspace.provision-max-attempts}，默认 3——即 2 次自动重试），达上限转
 * FAILED 待手动重试（{@link WorkspaceLifecycleAppService#retry}）。失败原因取异常
 * 归一化错误码 + 文案（ApplicationException 取 {@code code} + 格式化文案，其余取
 * {@code message}），落 {@code provision_error} 供工作台可见。</p>
 *
 * <p>线程模型：固定大小线程池（并发创建多项目各自独立置备、互不串行阻塞）+ 有界
 * 队列（防无限排队拖垮内存）。队列满时拒绝提交并直接标 failed——不静默悬置在
 * PROVISIONING。线程为 daemon（置备是尽力而为的收敛，进程退出不阻碍停机）。</p>
 *
 * <p>重启自愈与置备期收口（#64）：重启后 {@link #recoverPendingProvisions()} 对遗留
 * PROVISIONING 续置备（幂等预清，失败由既有重试→markFailed 收敛）；置备中销毁经
 * {@link #cancel(WorkspaceId)} 取消在途任务并等待其自清理，配合销毁方级联回收不留
 * 孤儿容器/网络/卷。</p>
 *
 * <p>事务：单条置备任务的落库收口（complete / markFailed）走仓储 {@code save} 的
 * 自动提交短事务——只读 {@code findById} 拿 detached 记录 → 状态机迁移 → {@code save}
 * merge 提交（同 {@link com.aieducenter.aiplatform.business.project.application.ProjectNamingAppService}
 * 的异步落库形态）。</p>
 */
@Service
@Slf4j
public class WorkspaceProvisionAppService implements DisposableBean {

    /** 置备线程池大小（可并行置备的工作区数；后续切片可按负载外化配置）。 */
    private static final int PROVISION_THREADS = 4;

    /** 置备提交队列容量（有界：满则拒绝提交转 failed，防无限排队）。 */
    private static final int PROVISION_QUEUE_CAPACITY = 100;

    /** 失败原因列宽（V17 provision_error VARCHAR(500)），超长截断。 */
    private static final int PROVISION_ERROR_MAX = 500;

    /** 取消在途置备的等待上界（createWorkspace 最坏 ≈ 镜像首构 1min + pg/redis 各 30s 就绪）。 */
    private static final Duration CANCEL_WAIT = Duration.ofMinutes(2);

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    /** 单条置备最大尝试次数（含首次；可配 app.workspace.provision-max-attempts）。 */
    private final int maxAttempts;
    /** 提交通道（生产 = 有界线程池；测试 = 直通同步）。 */
    private final Executor executor;
    /** 生产执行器生命周期（测试注入直通道时为 null）。 */
    private final ExecutorService ownedExecutor;
    /** 在途置备登记（workspaceId → 取消哨兵 + 完成闩）；销毁竞争时取消并等待其自清理（#64）。 */
    private final ConcurrentMap<WorkspaceId, InFlightProvision> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public WorkspaceProvisionAppService(EnvironmentBackend environmentBackend,
                                        WorkspaceRepository workspaceRepository,
                                        WorkspaceProperties properties) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.maxAttempts = Math.max(1, properties.getProvisionMaxAttempts());
        this.ownedExecutor = new ThreadPoolExecutor(
                PROVISION_THREADS, PROVISION_THREADS,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(PROVISION_QUEUE_CAPACITY),
                daemonThreadFactory("workspace-provision"),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor = ownedExecutor;
    }

    /** 测试便利构造（直通道，无生命周期；置备立即在当前线程同步执行，显式给重试上限）。 */
    WorkspaceProvisionAppService(EnvironmentBackend environmentBackend,
                                 WorkspaceRepository workspaceRepository,
                                 int maxAttempts,
                                 Executor executor) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.executor = executor;
        this.ownedExecutor = null;
    }

    /**
     * 提交后台置备（fire-and-forget）：调用方（创建编排）在记录落库、事务提交后调用，
     * 保证置备线程 {@code findById} 可见已提交记录。成功/失败收敛均在后台线程完成，
     * 不影响创建响应。
     */
    public void provision(WorkspaceId workspaceId, EnvKind kind) {
        InFlightProvision flight = new InFlightProvision();
        inFlight.put(workspaceId, flight);
        try {
            executor.execute(() -> provisionTask(workspaceId, kind, flight));
        } catch (RejectedExecutionException e) {
            inFlight.remove(workspaceId, flight);
            // 队列满（系统过载）：拒绝提交并标 failed，不留静默悬置的 PROVISIONING
            log.error("[workspace] {} 置备队列已满，拒绝提交转 failed", workspaceId.value());
            transitionToFailed(workspaceId, failureReason(e));
        }
    }

    /**
     * 取消在途置备（#64 置备期生命周期收口）：标记取消哨兵并等待在途任务落定——
     * 任务完成 createWorkspace 后见取消标志即回收刚落定资源（不转 READY）、未开始则
     * 直接空转返回；等待上界约 2 分钟，超时仅记日志（残留在途资源由销毁方级联清理
     * 兜底）。无在途置备时空转。
     */
    public void cancel(WorkspaceId workspaceId) {
        InFlightProvision flight = inFlight.get(workspaceId);
        if (flight == null) {
            return;
        }
        flight.cancel();
    }

    /**
     * 重启自愈（#64 重启收口）：对遗留的 PROVISIONING 工作区续置备——docker
     * createWorkspace 幂等预清同名残留，续置备安全；失败由 {@code provisionTask}
     * 既有重试→markFailed 收敛（「续置备或标 failed」自然覆盖，不静默悬置）。
     * 经 {@link WorkspaceProvisionRecovery}（ApplicationRunner）在上下文就绪后调用。
     */
    public void recoverPendingProvisions() {
        List<Workspace> pending = workspaceRepository.findByStatus(ProvisioningStatus.PROVISIONING);
        log.info("[workspace] 重启收口：{} 条遗留 PROVISIONING 工作区续置备", pending.size());
        for (Workspace workspace : pending) {
            provision(workspace.workspaceId(), workspace.getKind());
        }
    }

    @Override
    public void destroy() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    // ---------- 内部 ----------

    /**
     * 单条置备任务：docker 副作用先行（失败内部已级联回滚），成功回填转 READY；失败
     * 自动重试至 {@code maxAttempts} 次，达上限转 FAILED（带归一化失败原因）。
     */
    private void provisionTask(WorkspaceId workspaceId, EnvKind kind, InFlightProvision flight) {
        try {
            RuntimeException lastFailure = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                if (flight.isCancelled()) {
                    // 销毁竞争：置备前已取消，直接空转（物理回收归销毁方级联清理）
                    return;
                }
                try {
                    WorkspaceProvision provision = environmentBackend.createWorkspace(workspaceId, kind);
                    if (flight.isCancelled()) {
                        // 置备落定前被取消：回收本次刚落定的资源，不留孤儿（#64）
                        environmentBackend.destroyWorkspace(provision.handle());
                        return;
                    }
                    transitionToReady(workspaceId, provision);
                    return;
                } catch (RuntimeException e) {
                    lastFailure = e;
                    if (flight.isCancelled()) {
                        // 取消：不重试不标 failed（记录由销毁方删除），物理回收已由
                        // createWorkspace 内部级联回滚
                        return;
                    }
                    if (attempt < maxAttempts) {
                        // 自动重试：后端 createWorkspace 幂等预清同名残留，可安全重试
                        log.warn("[workspace] {} 后台置备第 {} 次失败（共 {} 次），重试",
                                workspaceId.value(), attempt, maxAttempts, e);
                    }
                }
            }
            // 失败：物理资源已在后端 createWorkspace 内级联回滚（#57）；这里落库收口 + 失败原因
            log.error("[workspace] {} 后台置备 {} 次全失败，转 failed（自诊断见归一化错误码）",
                    workspaceId.value(), maxAttempts, lastFailure);
            transitionToFailed(workspaceId, failureReason(lastFailure));
        } finally {
            flight.complete();
            inFlight.remove(workspaceId, flight);
        }
    }

    /** 成功收口（PROVISIONING → READY）：回填端口 + 中间件资源清单。 */
    private void transitionToReady(WorkspaceId workspaceId, WorkspaceProvision provision) {
        workspaceRepository.findById(workspaceId.id())
                .ifPresent(ws -> workspaceRepository.save(ws.complete(provision)));
    }

    /** 失败收口（PROVISIONING → FAILED）：落库标记 + 归一化失败原因；物理回收归后端级联回滚。 */
    private void transitionToFailed(WorkspaceId workspaceId, String reason) {
        try {
            workspaceRepository.findById(workspaceId.id())
                    .ifPresent(ws -> workspaceRepository.save(ws.markFailed(reason)));
        } catch (RuntimeException e) {
            // 状态机不变量由聚合 markFailed 自持（非 PROVISIONING 抛 WSP_009，多为并发已
            // complete/删除）；此处只兜底不炸后台线程
            log.error("[workspace] {} 失败态落库异常（记录保持现状）",
                    workspaceId.value(), e);
        }
    }

    /**
     * 归一化失败原因（落 provision_error，工作台可见）：ApplicationException 取错误码 +
     * 文案，其余取 message；截断到 {@code provision_error} 列宽（500），防长 stderr 撑爆
     * 失败态落库。
     */
    private String failureReason(RuntimeException e) {
        String reason = e instanceof ApplicationException applicationException
                ? applicationException.getCodeMessage().code() + "：" + applicationException.getMessage()
                : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return reason.length() <= PROVISION_ERROR_MAX ? reason : reason.substring(0, PROVISION_ERROR_MAX);
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 在途置备哨兵（#64）：取消标志 + 完成闩。置备任务在关键检查点（createWorkspace
     * 前后）读取消标志，finally 释放完成闩；销毁方经 {@link #cancel(WorkspaceId)} 置
     * 取消并等待完成，保证「销毁返回即无在途置备」。
     */
    private static final class InFlightProvision {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final CountDownLatch completed = new CountDownLatch(1);

        boolean isCancelled() {
            return cancelled.get();
        }

        void cancel() {
            cancelled.set(true);
            try {
                if (!completed.await(CANCEL_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("[workspace] 取消在途置备等待超时（{}ms），在途资源由销毁方级联清理兜底",
                            CANCEL_WAIT.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void complete() {
            completed.countDown();
        }
    }
}
