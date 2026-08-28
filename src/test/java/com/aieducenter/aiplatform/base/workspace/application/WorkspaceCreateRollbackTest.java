package com.aieducenter.aiplatform.base.workspace.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 创建落库失败的回滚面（#61 异步化口径）：记录落库失败时不提交后台置备、无任何
 * docker 副作用——副作用全部在事务提交后、后台线程内落定（「能对话」与「环境就绪」
 * 解耦的自然推论：记录先于副作用存在）。失败原样抛出。
 */
@SpringBootTest
class WorkspaceCreateRollbackTest {

    @Autowired
    private WorkspaceLifecycleAppService appService;

    @MockitoBean
    private WorkspaceRepository workspaceRepository;

    @MockitoBean
    private WorkspaceProvisionAppService provisioner;

    @MockitoBean
    private EnvironmentBackend environmentBackend;

    @Test
    void given_persist_failure_when_create_then_no_provision_no_docker() {
        when(workspaceRepository.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> appService.create(new CreateWorkspaceCommand(EnvKind.DEV)))
                .isInstanceOf(IllegalStateException.class);

        // 落库失败 → 不提交后台置备、无 docker 副作用（不留孤儿容器/网络/卷）
        verifyNoInteractions(provisioner);
        verifyNoInteractions(environmentBackend);
    }
}
