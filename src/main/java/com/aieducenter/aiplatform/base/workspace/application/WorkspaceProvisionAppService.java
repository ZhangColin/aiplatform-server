package com.aieducenter.aiplatform.base.workspace.application;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 工作区后台置备器（#58/#61 异步化核心）：创建即返回（记录 PROVISIONING 落库），
 * docker 置备转后台并行——有界队列 + 固定线程池驱动 {@link EnvironmentBackend}，
 * 成功经 {@code complete} 回填端口 + 资源转 READY，失败级联回滚（#57 口径，后端内部
 * 已回滚物理资源）+ 自诊断（WSP_008 等，后端归一化）后转 FAILED。
 *
 * <p>线程模型：固定大小线程池（并发创建多项目各自独立置备、互不串行阻塞）+ 有界
 * 队列（防无限排队拖垮内存）。队列满时拒绝提交并直接标 failed——不静默悬置在
 * PROVISIONING（重启收口归 #64）。线程为 daemon（置备是尽力而为的收敛，进程退出
 * 不阻碍停机；重启后遗留 PROVISIONING 收口另立票）。
 *
 * <p>事务：单条置备任务的落库收口（complete / markFailed）走仓储 {@code save} 的
 * 自动提交短事务——只读 {@code findById} 拿 detached 记录 → 状态机迁移 → {@code save}
 * merge 提交（同 {@link com.aieducenter.aiplatform.business.project.application.ProjectNamingAppService}
 * 的异步落库形态）。
 */
@Service
@Slf4j
public class WorkspaceProvisionAppService implements DisposableBean {

    /** 置备线程池大小（可并行置备的工作区数；后续切片可按负载外化配置）。 */
    private static final int PROVISION_THREADS = 4;

    /** 置备提交队列容量（有界：满则拒绝提交转 failed，防无限排队）。 */
    private static final int PROVISION_QUEUE_CAPACITY = 100;

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    /** 提交通道（生产 = 有界线程池；测试 = 直通同步）。 */
    private final Executor executor;
    /** 生产执行器生命周期（测试注入直通道时为 null）。 */
    private final ExecutorService ownedExecutor;

    @Autowired
    public WorkspaceProvisionAppService(EnvironmentBackend environmentBackend,
                                        WorkspaceRepository workspaceRepository) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.ownedExecutor = new ThreadPoolExecutor(
                PROVISION_THREADS, PROVISION_THREADS,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(PROVISION_QUEUE_CAPACITY),
                daemonThreadFactory("workspace-provision"),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor = ownedExecutor;
    }

    /** 测试便利构造（直通道，无生命周期；置备立即在当前线程同步执行）。 */
    WorkspaceProvisionAppService(EnvironmentBackend environmentBackend,
                                 WorkspaceRepository workspaceRepository,
                                 Executor executor) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.executor = executor;
        this.ownedExecutor = null;
    }

    /**
     * 提交后台置备（fire-and-forget）：调用方（创建编排）在记录落库、事务提交后调用，
     * 保证置备线程 {@code findById} 可见已提交记录。成功/失败收敛均在后台线程完成，
     * 不影响创建响应。
     */
    public void provision(WorkspaceId workspaceId, EnvKind kind) {
        try {
            executor.execute(() -> provisionTask(workspaceId, kind));
        } catch (RejectedExecutionException e) {
            // 队列满（系统过载）：拒绝提交并标 failed，不留静默悬置的 PROVISIONING
            log.error("[workspace] {} 置备队列已满，拒绝提交转 failed", workspaceId.value());
            transitionToFailed(workspaceId);
        }
    }

    @Override
    public void destroy() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    // ---------- 内部 ----------

    /** 单条置备任务：docker 副作用先行（失败内部已级联回滚），再落库收口到终态。 */
    private void provisionTask(WorkspaceId workspaceId, EnvKind kind) {
        try {
            WorkspaceProvision provision = environmentBackend.createWorkspace(workspaceId, kind);
            transitionToReady(workspaceId, provision);
        } catch (RuntimeException e) {
            // 失败：物理资源已在后端 createWorkspace 内级联回滚（#57）；这里只落库收口
            log.error("[workspace] {} 后台置备失败，转 failed（自诊断见后端归一化错误码）",
                    workspaceId.value(), e);
            transitionToFailed(workspaceId);
        }
    }

    /** 成功收口（PROVISIONING → READY）：回填端口 + 中间件资源清单。 */
    private void transitionToReady(WorkspaceId workspaceId, WorkspaceProvision provision) {
        workspaceRepository.findById(workspaceId.id())
                .ifPresent(ws -> workspaceRepository.save(ws.complete(provision)));
    }

    /** 失败收口（PROVISIONING → FAILED）：只落库标记，物理回收归后端级联回滚。 */
    private void transitionToFailed(WorkspaceId workspaceId) {
        try {
            workspaceRepository.findById(workspaceId.id())
                    .ifPresent(ws -> workspaceRepository.save(ws.markFailed()));
        } catch (RuntimeException e) {
            // 状态机不变量由聚合 markFailed 自持（非 PROVISIONING 抛 WSP_009）；此处只兜底不炸后台线程
            log.error("[workspace] {} 失败态落库异常（记录保持现状，重启收口归 #64）",
                    workspaceId.value(), e);
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
