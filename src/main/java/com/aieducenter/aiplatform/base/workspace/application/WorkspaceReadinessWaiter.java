package com.aieducenter.aiplatform.base.workspace.application;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 环境就绪等待（#62 文件操作级隐式等待）：置备中的工作区在执行需要环境的能力
 * （exec / 预览 / 打包）前，轮询仓储直到 READY；FAILED 抛失败、超时抛超时——都不静默
 * 悬挂。等待只放在文件操作处，不进对话入口（{@code handleOf} 置备中照常可取，端口 0）。
 *
 * <p>状态收敛归后台置备器（{@link WorkspaceProvisionAppService}，成功 complete→READY /
 * 失败 markFailed→FAILED），本组件是消费侧：只「等」不「改」——超时不翻状态（遗留
 * PROVISIONING 的收口归 #64 重启自愈），只让本次需要环境的操作失败，避免与仍在飞的后台
 * 置备竞争翻态致孤儿。
 */
@Component
@Slf4j
public class WorkspaceReadinessWaiter {

    /** 置备等待超时（docker 置备最坏 ≈ 镜像首构 1min + pg/redis 各 30s 就绪，留余量）。 */
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    /** 就绪轮询间隔（与 docker 后端资源探针同频）。 */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private final WorkspaceRepository workspaceRepository;
    private final Duration timeout;
    private final Duration pollInterval;

    @Autowired
    public WorkspaceReadinessWaiter(WorkspaceRepository workspaceRepository) {
        this(workspaceRepository, TIMEOUT, POLL_INTERVAL);
    }

    /** 测试构造：注入短超时/间隔以验收收敛（生产走 3min/500ms）。 */
    WorkspaceReadinessWaiter(WorkspaceRepository workspaceRepository,
                             Duration timeout, Duration pollInterval) {
        this.workspaceRepository = workspaceRepository;
        this.timeout = timeout;
        this.pollInterval = pollInterval;
    }

    /**
     * 取「已就绪」的工作区：READY 即回（句柄带真实端口）；PROVISIONING 轮询至 READY；
     * FAILED 抛 WSP_010（置备已败，不会就绪）；超时抛 WSP_011（不静默悬挂）。工作区在
     * 等待中被删则抛 WSP_001（404）。
     */
    public Workspace awaitReady(Workspace workspace) {
        WorkspaceId workspaceId = workspace.workspaceId();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        Workspace current = workspace;
        while (true) {
            if (current.getStatus() == ProvisioningStatus.READY) {
                return current;
            }
            if (current.getStatus() == ProvisioningStatus.FAILED) {
                throw new ApplicationException(WorkspaceMessage.WORKSPACE_PROVISION_FAILED);
            }
            if (System.currentTimeMillis() >= deadline) {
                log.warn("[workspace] {} 置备等待超时（{}ms），需要环境的操作失败", workspaceId.value(),
                        timeout.toMillis());
                throw new ApplicationException(WorkspaceMessage.WORKSPACE_PROVISION_TIMEOUT);
            }
            sleep();
            current = workspaceRepository.findById(workspaceId.id())
                    .orElseThrow(() -> new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND));
        }
    }

    private void sleep() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplicationException(WorkspaceMessage.WORKSPACE_PROVISION_TIMEOUT);
        }
    }
}
