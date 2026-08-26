package com.aieducenter.aiplatform.business.project.infrastructure;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.chatagent.domain.port.PrdArtifactPort;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectEventTypes;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link PrdArtifactAdapter}（#49 savePrd 效果半边）：路径正本 = 主链产物常量；
 * 落盘回调 = 置「PRD 已产出」状态位（事务）+ 发 document-updated（提交后发射，
 * payload 按 #41 契约）；修订再执行只前进（时间戳刷新、事件每执行必发）；工作区
 * 无项目 → PRJ_001 不发事件。
 */
@SpringBootTest
class PrdArtifactAdapterTest {

    @Autowired
    private PrdArtifactPort prdArtifactPort;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** SSE 发射边收口（真实链路无订阅者，发射本身是观测缝）。 */
    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_projects WHERE workspace_id IN (8601, 8602)");
    }

    @Test
    void given_contract_when_workspacePath_then_main_chain_artifact() {
        // 路径正本与 PRD 读端点同源（单一事实，勿散落字面量）
        assertThat(prdArtifactPort.workspacePath()).isEqualTo(ProjectMainChain.PRD_ARTIFACT);
    }

    @Test
    void given_prd_written_when_onWritten_then_status_bit_set_and_document_updated_published() {
        Long projectId = persistedProject(8601L).getId();

        prdArtifactPort.onWritten("8601");

        // 状态位（G1 门谓词输入）：置位落库
        assertThat(bitOf(projectId)).isNotNull();
        // 事件（#41 契约）：projectId + documentType=PRD
        assertThat(publishedPayload()).containsEntry(
                ProjectEventTypes.PROJECT_ID_FIELD, projectId.toString())
                .containsEntry(ProjectEventTypes.DOCUMENT_TYPE_FIELD,
                        ProjectEventTypes.DOCUMENT_TYPE_PRD);
    }

    @Test
    void given_prd_written_twice_when_revision_then_bit_refreshed_and_event_republished() {
        Project project = persistedProject(8602L);

        prdArtifactPort.onWritten("8602");
        LocalDateTime first = bitOf(project.getId());
        prdArtifactPort.onWritten("8602");

        // 修订再执行：三更新——位刷新（时间戳只前进）+ 事件每执行必发
        assertThat(bitOf(project.getId())).isAfterOrEqualTo(first);
        verify(notificationAppService, times(2))
                .publish(eq(ProjectEventTypes.DOCUMENT_UPDATED), anyMap());
    }

    @Test
    void given_unknown_workspace_when_onWritten_then_prj_001_no_event() {
        assertThatThrownBy(() -> prdArtifactPort.onWritten("999999"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());

        verify(notificationAppService, never()).publish(anyString(), anyMap());
    }

    // ---------- 内部 ----------

    private Map<String, Object> publishedPayload() {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationAppService)
                .publish(eq(ProjectEventTypes.DOCUMENT_UPDATED), payload.capture());
        return payload.getValue();
    }

    private Project persistedProject(long workspaceId) {
        return projectRepository.save(Project.create("PRD 产物适配器测试", null, "opencode",
                workspaceId, null));
    }

    private LocalDateTime bitOf(Long projectId) {
        return projectRepository.findById(projectId).orElseThrow().getPrdProducedAt();
    }
}
