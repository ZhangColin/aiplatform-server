package com.aieducenter.aiplatform.business.project.infrastructure;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.chatagent.domain.port.PrdArtifactPort;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectEventTypes;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * PRD 产物业务契约真实现（#49，base.chatagent 的 savePrd 工具效果半边）：路径
 * 正本 = 主链产物 {@link ProjectMainChain#PRD_ARTIFACT}（PRD 读端点同源）；
 * 落盘成功回调 = 按工作区寻址项目置「PRD 已产出」状态位（G1 门谓词输入）+
 * 发 document-updated（#41 契约，前端失效为主消费）。端口在消费方
 * （base.chatagent），实现归事实持有方（business.project，照 OpenBugQueryPort
 * 跨上下文先例）。
 *
 * <p>SSE 事务提交后发射（编排层发射制，ADR-0001）：置位短事务先行，事件随后；
 * 置位失败抛出——工具回失败结果，模型可再次保存重试（写文件幂等覆盖）。</p>
 */
@Component
@Adapter(PortType.CLIENT)
public class PrdArtifactAdapter implements PrdArtifactPort {

    private final ProjectRepository projectRepository;
    private final PlatformNotificationAppService notificationAppService;
    private final TransactionTemplate transactionTemplate;

    public PrdArtifactAdapter(ProjectRepository projectRepository,
            PlatformNotificationAppService notificationAppService,
            TransactionTemplate transactionTemplate) {
        this.projectRepository = projectRepository;
        this.notificationAppService = notificationAppService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public String workspacePath() {
        return ProjectMainChain.PRD_ARTIFACT;
    }

    @Override
    public void onWritten(String workspaceId) {
        String projectId = transactionTemplate.execute(tx -> {
            Project project = projectRepository.findByWorkspaceId(Long.parseLong(workspaceId))
                    .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
            project.markPrdProduced();
            projectRepository.save(project);
            return project.getId().toString();
        });
        notificationAppService.publish(ProjectEventTypes.DOCUMENT_UPDATED, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, projectId,
                ProjectEventTypes.DOCUMENT_TYPE_FIELD, ProjectEventTypes.DOCUMENT_TYPE_PRD));
    }
}
