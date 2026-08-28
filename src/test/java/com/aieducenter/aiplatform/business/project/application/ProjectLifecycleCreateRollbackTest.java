package com.aieducenter.aiplatform.business.project.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.business.project.application.dto.command.CreateProjectCommand;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 建项目落库失败的回滚面（照片1b workspace 的兜底形态）：库记录失败时回收
 * 已落定的工作区物理资源，不留孤儿容器；失败原样抛出，不发 SSE、不跑 BA。
 */
@SpringBootTest
class ProjectLifecycleCreateRollbackTest {

    @Autowired
    private ProjectLifecycleAppService appService;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @MockitoBean
    private BaInterviewAppService baInterviewAppService;

    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    @MockitoBean
    private ProjectRepository projectRepository;

    @MockitoBean
    private IterationRepository iterationRepository;

    @Test
    void given_persist_failure_when_create_then_workspace_reclaimed_and_propagated() {
        when(workspaceLifecycleAppService.create(any())).thenReturn(new WorkspaceResponse(
                "9300", EnvKind.DEV, "开发环境", "aiplatform-dev-300", "net-x", 14096, 18081,
                ProvisioningStatus.READY, "就绪", null, java.util.List.of(), java.time.LocalDateTime.now()));
        when(projectRepository.save(any(com.aieducenter.aiplatform.business.project.domain.aggregate.Project.class)))
                .thenThrow(new IllegalStateException("db down"));
        doThrow(new IllegalStateException("unreachable")).when(iterationRepository)
                .save(any(com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration.class));

        assertThatThrownBy(() -> appService.create(
                new CreateProjectCommand("做一个官网")))
                .isInstanceOf(IllegalStateException.class);

        // 落库失败 → 回收已落定的工作区；不发射任何 SSE、不开 BA 访谈、不取名
        verify(workspaceLifecycleAppService).destroy("9300");
        verifyNoInteractions(notificationAppService);
        verifyNoInteractions(baInterviewAppService);
        verify(notificationAppService, never()).publish(any(), any());
    }
}
