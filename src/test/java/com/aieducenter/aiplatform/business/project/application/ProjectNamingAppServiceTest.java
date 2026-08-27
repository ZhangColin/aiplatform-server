package com.aieducenter.aiplatform.business.project.application;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.base.chatagent.application.ChatAgentAppService;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.error.ChatAgentMessage;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectNamingAppService}（#39）：创建后异步 LLM 取名——naming-{projectId}
 * 静默轻调用（无 SSE/无等待点）→ 净化（剥包裹引号/取首行/上限校验，红线 = 不以
 * requirement 截取兜底）→ 仅占位名时覆写；取名失败/超时保占位不炸创建路径。
 */
@ExtendWith(MockitoExtension.class)
class ProjectNamingAppServiceTest {

    @Mock
    private ChatAgentAppService chatAgentAppService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private PlatformNotificationAppService notificationAppService;

    @Test
    void given_wrapped_reply_when_name_async_then_sanitized_name_renames_placeholder() {
        Project project = placeholderProject();
        when(projectRepository.findById(42L)).thenReturn(Optional.of(project));
        when(chatAgentAppService.converseSilently(any()))
                .thenReturn(new ChatAgentReply("run-n", "\n「品牌官网」\n"));
        ProjectNamingAppService service = service();

        service.nameAsync(42L, "做一个高端家具品牌官网，带产品册和预约");

        ArgumentCaptor<Project> saved = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("品牌官网"); // 首行 + 剥引号净化
    }

    @Test
    void given_command_when_name_async_then_silent_lightweight_shape() {
        // 轻调用形态：naming-{projectId} 会话、本地工作区（不碰项目 dev 工作区）、
        // 计量归属 projectId + role=NAMING 用途标记、requirement 原文为 prompt
        when(projectRepository.findById(43L)).thenReturn(Optional.of(placeholderProject()));
        when(chatAgentAppService.converseSilently(any()))
                .thenReturn(new ChatAgentReply("run-n", "商城小程序"));
        ProjectNamingAppService service = service();

        service.nameAsync(43L, "做个商城小程序");

        ArgumentCaptor<ChatAgentCommand> command =
                ArgumentCaptor.forClass(ChatAgentCommand.class);
        verify(chatAgentAppService).converseSilently(command.capture());
        assertThat(command.getValue().sessionId()).isEqualTo("naming-43");
        assertThat(command.getValue().prompt()).isEqualTo("做个商城小程序");
        assertThat(command.getValue().systemPrompt()).isNotBlank(); // 取名协议（只输出名称）
        assertThat(command.getValue().workspaceId()).isNull();
        assertThat(command.getValue().usageContext().subject()).isEqualTo("43");
        assertThat(command.getValue().usageContext().dims())
                .containsEntry(ProjectAgentTaskAppService.DIM_ROLE, "NAMING");
        assertThat(command.getValue().runId()).isNotBlank();
    }

    @Test
    void given_blank_or_oversized_reply_when_name_async_then_placeholder_kept() {
        // 净化不过关（空/超 DB 上限）→ 保占位；不以 requirement 截取兜底（红线）
        Project project = placeholderProject();
        when(chatAgentAppService.converseSilently(any()))
                .thenReturn(new ChatAgentReply("run-n", "  \n「」  "));
        ProjectNamingAppService service = service();

        service.nameAsync(44L, "做一个官网");

        verify(projectRepository, never()).save(any());
        assertThat(project.getName()).isEqualTo(Project.PLACEHOLDER_NAME);

        when(chatAgentAppService.converseSilently(any()))
                .thenReturn(new ChatAgentReply("run-n2", "字".repeat(101)));
        service.nameAsync(44L, "做一个官网");
        verify(projectRepository, never()).save(any());
        assertThat(project.getName()).isEqualTo(Project.PLACEHOLDER_NAME);
    }

    @Test
    void given_blank_requirement_when_name_async_then_no_converse() {
        // 空需求无输入可依：不发起轻调用（占位即终态，改名端点可改）
        ProjectNamingAppService service = service();

        service.nameAsync(48L, " ");

        verifyNoInteractions(chatAgentAppService);
        verify(projectRepository, never()).save(any());
    }

    @Test
    void given_converse_failure_when_name_async_then_swallowed_and_placeholder_kept() {
        // 引擎不可用/超时：取名失败保占位，不炸调用方（创建不受影响）
        when(chatAgentAppService.converseSilently(any()))
                .thenThrow(new DomainException(ChatAgentMessage.CONVERSE_FAILED, "模型超时"));
        ProjectNamingAppService service = service();

        assertThatCode(() -> service.nameAsync(45L, "做一个官网"))
                .doesNotThrowAnyException();

        verify(projectRepository, never()).save(any());
    }

    @Test
    void given_user_renamed_when_name_completes_then_not_overwritten() {
        // 取名在飞时用户已改名（#43）→ 不覆写（占位守卫：只顶替占位名）
        Project project = placeholderProject();
        project.rename("我起的名字");
        when(projectRepository.findById(46L)).thenReturn(Optional.of(project));
        when(chatAgentAppService.converseSilently(any()))
                .thenReturn(new ChatAgentReply("run-n", "LLM 的名字"));
        ProjectNamingAppService service = service();

        service.nameAsync(46L, "做一个官网");

        verify(projectRepository, never()).save(any());
        assertThat(project.getName()).isEqualTo("我起的名字");
    }

    @Test
    void given_project_deleted_when_name_completes_then_noop() {
        when(projectRepository.findById(47L)).thenReturn(Optional.empty());
        when(chatAgentAppService.converseSilently(any()))
                .thenReturn(new ChatAgentReply("run-n", "名字"));
        ProjectNamingAppService service = service();

        assertThatCode(() -> service.nameAsync(47L, "做一个官网"))
                .doesNotThrowAnyException();
        verify(projectRepository, never()).save(any());
    }

    // ---------- #52：取名落定的 project-renamed 触达 ----------

    @Test
    void given_placeholder_when_name_lands_then_project_renamed_published() {
        // 取名落库（renameIfPlaceholder 为真）→ 发 project-renamed（projectId +
        // projectName）：前端失效 projects 域重拉，停留中的页面上名字静默浮现
        Project project = placeholderProject();
        when(projectRepository.findById(49L)).thenReturn(Optional.of(project));
        when(chatAgentAppService.converseSilently(any()))
                .thenReturn(new ChatAgentReply("run-n", "「品牌官网」"));
        ProjectNamingAppService service = service();

        service.nameAsync(49L, "做一个高端家具品牌官网");

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationAppService).publish(eq(ProjectEventTypes.PROJECT_RENAMED),
                payload.capture());
        assertThat(payload.getValue()).containsOnly(
                Map.entry("projectId", "49"), Map.entry("projectName", "品牌官网"));
    }

    @Test
    void given_user_renamed_when_name_not_applied_then_no_event() {
        // 守卫不覆写 = 无变化无事件（落位守卫不发——改名端点兜底）
        Project project = placeholderProject();
        project.rename("我起的名字");
        when(projectRepository.findById(50L)).thenReturn(Optional.of(project));
        when(chatAgentAppService.converseSilently(any()))
                .thenReturn(new ChatAgentReply("run-n", "LLM 的名字"));
        ProjectNamingAppService service = service();

        service.nameAsync(50L, "做一个官网");

        verify(notificationAppService, never()).publish(anyString(), anyMap());
    }

    @Test
    void given_converse_failure_when_name_fails_then_no_event() {
        // 取名失败保占位（红线）→ 不发事件（失败静默是既有设计，无「取名挂了」误报）
        when(chatAgentAppService.converseSilently(any()))
                .thenThrow(new DomainException(ChatAgentMessage.CONVERSE_FAILED, "缺 API key"));
        ProjectNamingAppService service = service();

        service.nameAsync(51L, "做一个官网");

        verify(notificationAppService, never()).publish(anyString(), anyMap());
    }

    // ---------- 测试数据 ----------

    /** 直通执行器：nameAsync 提交即同步执行（异步语义在编排测试覆盖）。 */
    private ProjectNamingAppService service() {
        return new ProjectNamingAppService(chatAgentAppService, projectRepository,
                notificationAppService, Runnable::run);
    }

    private Project placeholderProject() {
        return Project.create(Project.PLACEHOLDER_NAME, null, "opencode", 900L, null);
    }
}
