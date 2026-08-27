package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.exception.GlobalExceptionHandler;

import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.business.project.application.ProjectAgentTaskAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectWaitAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectAgentTaskCommand;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectWaitSettleCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectAgentTaskResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectWaitResponse;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目智能体任务与等待点 REST 面（swagger 契约验收）：任务下发（role 可缺省）、
 * 等待点列表与三型答复、PRJ_004 → 409、AGT_007 → 409 透传。
 */
@WebMvcTest(ProjectAgentController.class)
@Import({ProjectAgentControllerTest.ExceptionAdviceConfig.class,
        com.cartisan.web.config.JacksonConfiguration.class})
class ProjectAgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectAgentTaskAppService taskAppService;

    @MockitoBean
    private ProjectWaitAppService waitAppService;

    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 1L, "project-agent-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_task_command_when_dispatch_then_run_id_returned() throws Exception {
        when(taskAppService.dispatchTask(eq(100L), any(ProjectAgentTaskCommand.class)))
                .thenReturn(new ProjectAgentTaskResponse("run-9", "ses-9", "opencode", RolePreset.DEV,
                        "开发工程师", "DEV", true));

        performAsUser(post("/api/projects/100/agent/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"按 PRD 开发\",\"role\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-9"))
                .andExpect(jsonPath("$.data.sessionId").value("ses-9"))
                .andExpect(jsonPath("$.data.role").value(2)) // DEV → Integer code
                .andExpect(jsonPath("$.data.roleName").value("开发工程师"))
                .andExpect(jsonPath("$.data.accepted").value(true));
    }

    @Test
    void given_task_without_prompt_then_rejected_as_400() throws Exception {
        performAsUser(post("/api/projects/100/agent/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void given_stage_without_default_role_when_dispatch_then_prj_004_409() throws Exception {
        when(taskAppService.dispatchTask(eq(100L), any(ProjectAgentTaskCommand.class)))
                .thenThrow(new ApplicationException(ProjectMessage.ROLE_REQUIRED));

        performAsUser(post("/api/projects/100/agent/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"测一下\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("当前阶段无默认角色，需显式指定角色卡"));
    }

    @Test
    void given_pending_waits_when_list_then_bridged() throws Exception {
        when(waitAppService.pendingWaits(100L)).thenReturn(List.of(
                new ProjectWaitResponse("wait-1", WaitKind.QUESTION, null, WaitStatus.PENDING,
                        null, "用哪个框架?", "ses-9", "run-9", "que_1",
                        Map.of("options", List.of("React", "Vue")), null, null, null,
                        null)));

        performAsUser(get("/api/projects/100/agent/waits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].waitId").value("wait-1"))
                .andExpect(jsonPath("$.data[0].kind").value(1)) // BaseEnum → Integer code（QUESTION）
                .andExpect(jsonPath("$.data[0].kindName").value("问答"))
                .andExpect(jsonPath("$.data[0].statusName").value("待处理"))
                .andExpect(jsonPath("$.data[0].settleOutcomeName").value(nullValue()))
                .andExpect(jsonPath("$.data[0].body.options[0]").value("React"));
    }

    @Test
    void given_run_when_cancel_then_ok_void_body() throws Exception {
        // 票 #38：终止运行端点——best-effort 恒 200 空转（响应体无 run 状态承诺）
        performAsUser(post("/api/projects/100/agent/runs/run-9/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(taskAppService).cancelRun(100L, "run-9");
    }

    @Test
    void given_unresolvable_run_when_cancel_then_agt_011_404() throws Exception {
        doThrow(new ApplicationException(
                com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage.RUN_NOT_FOUND))
                .when(taskAppService).cancelRun(100L, "run-none");

        performAsUser(post("/api/projects/100/agent/runs/run-none/cancel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void given_answer_settlement_when_settle_then_ok_and_bridged() throws Exception {
        performAsUser(post("/api/projects/100/agent/waits/wait-1/settle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"answer\",\"answers\":[[\"React\"]]}"))
                .andExpect(status().isOk());

        verify(waitAppService).settle(eq(100L), eq("wait-1"),
                any(ProjectWaitSettleCommand.class));
    }

    @Test
    void given_permission_settlement_when_settle_then_ok() throws Exception {
        performAsUser(post("/api/projects/100/agent/waits/wait-2/settle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"permission\",\"approve\":false}"))
                .andExpect(status().isOk());
        verify(waitAppService).settle(eq(100L), eq("wait-2"),
                argThat(cmd -> Boolean.FALSE.equals(cmd.approve())));
    }

    @Test
    void given_unknown_type_when_settle_then_400() throws Exception {
        org.mockito.Mockito.doThrow(new ApplicationException(
                com.cartisan.core.exception.BaseCodeMessage.BAD_REQUEST,
                "type 取值必须是 answer / permission / deferred: kill"))
                .when(waitAppService).settle(eq(100L), eq("wait-3"),
                        any(ProjectWaitSettleCommand.class));

        performAsUser(post("/api/projects/100/agent/waits/wait-3/settle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"kill\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void given_task_without_role_param_when_dispatch_then_role_omitted() throws Exception {
        when(taskAppService.dispatchTask(eq(100L), any(ProjectAgentTaskCommand.class)))
                .thenReturn(new ProjectAgentTaskResponse("run-10", null, "opencode", RolePreset.BA,
                        "需求分析师", "BA", false));

        performAsUser(post("/api/projects/100/agent/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"梳理需求\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value(1)) // BA → Integer code
                .andExpect(jsonPath("$.data.roleName").value("需求分析师"));
        // role 缺省透传为空（阶段默认角色由应用层解析）
        verify(taskAppService).dispatchTask(eq(100L),
                argThat(cmd -> cmd.role() == null));
    }

    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
