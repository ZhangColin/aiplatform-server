package com.aieducenter.aiplatform.business.workbench.endpoints.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.cartisan.web.exception.GlobalExceptionHandler;

import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.GateReadyResponse;
import com.aieducenter.aiplatform.business.task.application.TaskQueryAppService;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskTodoSource;
import com.aieducenter.aiplatform.business.workbench.application.TodoAppService;
import com.aieducenter.aiplatform.config.WebMvcConfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/todos 契约（A2 §4/§5 + A4 §7 任务型接线）：统一信封 data: TodoItem[]，
 * view 缺省 dev（AGENT_WAIT/GATE_PENDING/TASK_SUBMITTED/RETEST_READY）、opc =
 * NEW_TASK/TASK_REJECTED（assignee=me）；走真实链路（filter → 拦截器 →
 * controller → TodoAppService），无会话 401 统一信封。
 */
@WebMvcTest(TodoController.class)
@Import({TodoAppService.class, WebMvcConfig.class,
        TodoControllerTest.ExceptionAdviceConfig.class})
class TodoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BffSessionStore sessionStore;

    @MockitoBean
    private AgentWaitAppService agentWaitAppService;

    @MockitoBean
    private ProjectQueryAppService projectQueryAppService;

    @MockitoBean
    private TaskQueryAppService taskQueryAppService;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @Test
    void given_session_when_get_todos_then_items_in_envelope() throws Exception {
        login("sid-1");
        when(agentWaitAppService.listPendingWaits()).thenReturn(List.of(
                new WaitPointResponse("wait-1", "4242", "ses_1", "run-1", "ref_1",
                        WaitKind.QUESTION, null, WaitStatus.PENDING, null, null, null,
                        null, null, Instant.parse("2026-08-22T08:00:00Z"), null)));
        when(projectQueryAppService.projectIdByWorkspaceId(any()))
                .thenReturn(Map.of(4242L, "p1"));
        when(projectQueryAppService.listGateReady()).thenReturn(List.of(
                new GateReadyResponse("p9", "需求梳理", "user",
                        Instant.parse("2026-08-22T07:00:00Z"))));

        mockMvc.perform(get("/api/todos").cookie(sessionCookie("sid-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].type").value("AGENT_WAIT"))
                .andExpect(jsonPath("$.data[0].projectId").value("p1"))
                .andExpect(jsonPath("$.data[0].refId").value("wait-1"))
                .andExpect(jsonPath("$.data[0].title").value("智能体等待答复"))
                .andExpect(jsonPath("$.data[0].createdAt").exists())
                .andExpect(jsonPath("$.data[1].type").value("GATE_PENDING"))
                .andExpect(jsonPath("$.data[1].refId").value("p9"))
                .andExpect(jsonPath("$.data[1].title").value("「需求梳理」门待拍板"));
    }

    @Test
    void given_no_view_param_when_get_todos_then_defaults_to_dev() throws Exception {
        login("sid-1");
        when(agentWaitAppService.listPendingWaits()).thenReturn(List.of());
        when(projectQueryAppService.listGateReady()).thenReturn(List.of());
        when(taskQueryAppService.submittedTodoSources()).thenReturn(List.of());
        when(taskQueryAppService.retestReadyProjects()).thenReturn(List.of());

        mockMvc.perform(get("/api/todos").cookie(sessionCookie("sid-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(agentWaitAppService).listPendingWaits(); // 缺省走 dev 投影
        verify(projectQueryAppService).listGateReady();
    }

    @Test
    void given_opc_view_when_get_todos_then_task_types_only() throws Exception {
        login("sid-1");
        when(taskQueryAppService.publishedTodoSources(3897654321098765432L))
                .thenReturn(List.of(new TaskTodoSource("t1", "p1", "回归测试",
                        Instant.parse("2026-08-22T09:00:00Z"))));
        when(taskQueryAppService.rejectedTodoSources(3897654321098765432L))
                .thenReturn(List.of(new TaskTodoSource("t2", "p1", "冒烟测试",
                        Instant.parse("2026-08-22T10:00:00Z"))));

        mockMvc.perform(get("/api/todos").param("view", "opc")
                        .cookie(sessionCookie("sid-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].type").value("TASK_REJECTED")) // 新者在前
                .andExpect(jsonPath("$.data[0].refId").value("t2"))
                .andExpect(jsonPath("$.data[0].title").value("「冒烟测试」被驳回，待重新提交"))
                .andExpect(jsonPath("$.data[1].type").value("NEW_TASK"))
                .andExpect(jsonPath("$.data[1].refId").value("t1"))
                .andExpect(jsonPath("$.data[1].title").value("「回归测试」新任务，待开始"));

        verifyNoInteractions(agentWaitAppService, projectQueryAppService); // opc 不含 dev 两型
    }

    @Test
    void given_unknown_view_when_get_todos_then_400_envelope() throws Exception {
        login("sid-1");

        mockMvc.perform(get("/api/todos").param("view", "bogus")
                        .cookie(sessionCookie("sid-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Invalid request"));

        verifyNoInteractions(agentWaitAppService, projectQueryAppService, taskQueryAppService);
    }

    @Test
    void given_no_session_when_get_todos_then_401_with_unified_envelope() throws Exception {
        mockMvc.perform(get("/api/todos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    // ---------- 内部 ----------

    private void login(String sessionId) {
        when(sessionStore.get(sessionId)).thenReturn(Optional.of(
                new BffSession(3897654321098765432L, "张三", "idt", "at", "rt",
                        Instant.now().plusSeconds(60))));
    }

    private static Cookie sessionCookie(String sessionId) {
        return new Cookie(AuthCookies.SESSION_COOKIE_NAME, sessionId);
    }

    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
