package com.aieducenter.aiplatform.base.workspace.application;

import java.net.URI;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.event.ApplicationEventPublisher;

import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.application.event.PreviewReady;
import com.aieducenter.aiplatform.base.workspace.application.event.WorkspaceCreated;
import com.aieducenter.aiplatform.base.workspace.application.event.WorkspaceDestroyed;
import com.aieducenter.aiplatform.base.workspace.application.mapper.WorkspaceMapper;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 工作区生命周期用例（B0 蓝图 §2 片1b）：创建 / 查询 / exec / 预览 / 销毁级联。
 *
 * <p>创建异步化（#58/#61）：工作区记录（PROVISIONING 态）与 WorkspaceCreated 事件
 * 收进同一短事务（{@link TransactionTemplate}）立即返回，docker 置备转后台
 * （{@link WorkspaceProvisionAppService}）并行收敛到 ready / failed——「能对话」与「环境就绪」
 * 解耦，点创建即对话。创建不在请求线程内同步调用 docker；事务提交后才提交后台置备，
 * 置备线程 {@code findById} 保证可见已提交记录。落库失败时未产生任何 docker 副作用，
 * 无需回收（副作用全部在提交后、后台线程内落定）。</p>
 */
@Service
@Slf4j
public class WorkspaceLifecycleAppService {

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceProvisionAppService provisioner;

    public WorkspaceLifecycleAppService(EnvironmentBackend environmentBackend,
                                        WorkspaceRepository workspaceRepository,
                                        TransactionTemplate transactionTemplate,
                                        ApplicationEventPublisher eventPublisher,
                                        WorkspaceMapper workspaceMapper,
                                        WorkspaceProvisionAppService provisioner) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.workspaceMapper = workspaceMapper;
        this.provisioner = provisioner;
    }

    /**
     * 创建工作区：记录经置备状态机入口（registerPending，PROVISIONING、端口 0、
     * 确定性命名）落库并发布 WorkspaceCreated（AFTER_COMMIT）即返回；docker 副作用
     * 转后台 {@link WorkspaceProvisionAppService} 并行收敛——成功经 complete 回填端口 + 资源
     * 转 READY，失败级联回滚（#57）+ 自诊断转 FAILED。
     */
    public WorkspaceResponse create(CreateWorkspaceCommand command) {
        WorkspaceId workspaceId = WorkspaceId.generate();
        EnvKind kind = command.kindOrDefault();
        WorkspaceResponse response = transactionTemplate.execute(status -> {
            Workspace workspace = workspaceRepository.save(
                    Workspace.registerPending(workspaceId, kind));
            eventPublisher.publishApplicationEvent(WorkspaceCreated.of(workspaceId, kind));
            return workspaceMapper.convert(workspace);
        });
        // 事务提交后异步置备（此时记录已可见，置备线程可接回并落库收口）
        provisioner.provision(workspaceId, kind);
        return response;
    }

    /**
     * 查询工作区（重启接回的验证面：记录仍在，句柄可从记录重建）。
     */
    public WorkspaceResponse get(String workspaceId) {
        return workspaceMapper.convert(requireWorkspace(workspaceId));
    }

    /**
     * 取工作区运行时句柄（环境能力面的操作锚点；片2 agentengine 等底座消费方的
     * 跨上下文出口——{@link WorkspaceHandle} 是 base 内部值对象，非对外 REST 契约）。
     * 不存在即 WSP_001（404）。
     */
    public WorkspaceHandle handleOf(String workspaceId) {
        return requireWorkspace(workspaceId).toHandle();
    }

    /**
     * 在工作区容器内执行命令取结果（exitCode 非 0 是命令失败，不是环境故障）。
     */
    public ExecResultResponse exec(String workspaceId, WorkspaceExecCommand command) {
        Workspace workspace = requireWorkspace(workspaceId);
        ExecResult result = environmentBackend.exec(workspace.toHandle(), command.command());
        return new ExecResultResponse(result.stdout(), result.stderr(), result.exitCode());
    }

    /**
     * 暴露预览并发 PreviewReady（AFTER_COMMIT）。发布走短事务——订阅方的事务性
     * 监听依赖一个真实提交的事务，这里预览无落库、事务体只含发布。
     */
    public URI exposePreview(String workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        URI url = environmentBackend.exposePort(workspace.toHandle(),
                EnvironmentBackend.DEV_PREVIEW_CONTAINER_PORT);
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishApplicationEvent(
                PreviewReady.of(workspace.workspaceId(), url)));
        return url;
    }

    /**
     * 打包工作区源码为 tar.gz 字节流（排除 .env 机密与 node_modules；下载交付
     * 的文件名/HTTP 头归调用方，本层只出字节）。
     */
    public byte[] packSource(String workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        return environmentBackend.packSource(workspace.toHandle());
    }

    /**
     * 销毁工作区：物理资源先级联清理（容器→网络→卷，后端尽力而为），记录删除的
     * 事务内发 WorkspaceDestroyed（AFTER_COMMIT）。物理清理失败不阻断记录删除——
     * Docker 侧残留以真实状态为准，可重建句柄后重试销毁。
     */
    public void destroy(String workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        environmentBackend.destroyWorkspace(workspace.toHandle());
        transactionTemplate.executeWithoutResult(status -> {
            workspaceRepository.delete(workspace);
            eventPublisher.publishApplicationEvent(
                    WorkspaceDestroyed.of(workspace.workspaceId()));
        });
    }

    private Workspace requireWorkspace(String workspaceId) {
        return workspaceRepository.findById(parseId(workspaceId))
                .orElseThrow(() -> new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND));
    }

    private long parseId(String workspaceId) {
        try {
            long id = Long.parseLong(workspaceId);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // 非数值 → 落到下方统一 404
        }
        // 非数值/非正数即不存在的标识，语义上同 404
        throw new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND);
    }
}
