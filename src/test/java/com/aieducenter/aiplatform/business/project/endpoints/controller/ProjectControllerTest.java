package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.time.LocalDateTime;
import java.util.List;

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

import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.CreateProjectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectCreatedResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目 REST 面（swagger 契约验收）：ApiResponse 信封、建项目响应携带自动 BA
 * runId、类型 Integer code 双向、PRJ_001 → 404、参数校验。
 */
@WebMvcTest(ProjectController.class)
@Import({ProjectControllerTest.ExceptionAdviceConfig.class,
        com.cartisan.web.config.JacksonConfiguration.class})
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectLifecycleAppService appService;

    /** A2 起全 /api/** 拦截——MVC 契约测试不走登录链，夹具直接注 RequestContext。 */
    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 1L, "project-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_valid_command_when_create_then_wrapped_with_ba_run() throws Exception {
        when(appService.create(any(CreateProjectCommand.class))).thenReturn(
                new ProjectCreatedResponse(new ProjectResponse("100", "官网 demo",
                        ProjectType.WEBSITE, "官网", "opencode", "900", "BA", "需求梳理",
                        ProjectResponse.STATUS_IN_PROGRESS, "开发中", 0, false,
                        LocalDateTime.of(2026, 8, 22, 10, 0)), "run-1", true));

        performAsUser(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"官网 demo\",\"engine\":\"opencode\","
                                + "\"requirement\":\"做一个官网\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.project.id").value("100"))
                .andExpect(jsonPath("$.data.project.type").value(1)) // BaseEnum → Integer code
                .andExpect(jsonPath("$.data.project.stage").value("BA"))
                .andExpect(jsonPath("$.data.project.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.accepted").value(true));
        verify(appService).create(argThat(cmd -> "做一个官网".equals(cmd.requirement())));
    }

    @Test
    void given_blank_name_when_create_then_rejected_as_400() throws Exception {
        performAsUser(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void given_projects_when_list_then_wrapped_array() throws Exception {
        when(appService.list()).thenReturn(List.of(new ProjectResponse("100", "官网",
                ProjectType.WEBSITE, "官网", "opencode", "900", null, null,
                ProjectResponse.STATUS_DELIVERED, "已交付", null, false, null)));

        performAsUser(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("100"))
                .andExpect(jsonPath("$.data[0].status").value("DELIVERED"));
    }

    @Test
    void given_unknown_project_when_get_then_prj_001_mapped_to_404() throws Exception {
        when(appService.get(404L))
                .thenThrow(new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));

        performAsUser(get("/api/projects/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void given_non_numeric_id_when_get_then_404() throws Exception {
        performAsUser(get("/api/projects/abc"))
                .andExpect(status().isNotFound());
    }

    @Test
    void given_project_when_delete_then_ok_without_data() throws Exception {
        performAsUser(delete("/api/projects/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(appService).delete(100L);
    }

    /**
     * MVC 切片不含 cartisan-web autoconfig，手动注册其全局异常处理器
     * （BaseEnum Jackson 序列化经类级 @Import 引入，对齐生产序列化行为）。
     */
    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
