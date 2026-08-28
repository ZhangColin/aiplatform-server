package com.aieducenter.aiplatform.base.workspace.endpoints.controller;

import java.time.LocalDateTime;
import java.util.List;

import com.cartisan.core.context.RequestContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;

import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.exception.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作区最小 REST 面（swagger 契约验收）：ApiResponse 信封、错误映射、参数校验。
 * MVC 切片窄上下文（无数据面）；全局异常处理器为 cartisan-web autoconfig 注册，
 * 切片不含，手动挂上以覆盖 WSP_001 → 404 映射。
 */
@WebMvcTest(WorkspaceController.class)
@Import({WorkspaceControllerTest.ExceptionAdviceConfig.class,
        com.cartisan.web.config.JacksonConfiguration.class})
class WorkspaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkspaceLifecycleAppService appService;

    /**
     * A2 起全 /api/** 拦截（切片经 WebMvcConfigurer 扫入拦截器）——MVC 契约测试
     * 不走登录链，夹具直接注 RequestContext（A2 规格 §2 表 4 既有约定）。
     */
    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 1L, "workspace-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_dev_workspace_when_create_then_wrapped_in_api_response() throws Exception {
        when(appService.create(any(CreateWorkspaceCommand.class)))
                .thenReturn(new WorkspaceResponse("100", EnvKind.DEV, "开发",
                        "ws-100-dev", "net-100", 20000, 20001,
                        ProvisioningStatus.READY, "就绪", null,
                        List.of(new WorkspaceResponse.MiddlewareResourceResponse(
                                MiddlewareKind.POSTGRESQL, "PostgreSQL",
                                "pg-100", 35432, "postgresql://pg")),
                        LocalDateTime.of(2026, 8, 21, 12, 0)));

        performAsUser(post("/api/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.workspaceId").value("100"))
                .andExpect(jsonPath("$.data.kind").value(1))
                .andExpect(jsonPath("$.data.containerName").value("ws-100-dev"))
                .andExpect(jsonPath("$.data.resources[0].url").value("postgresql://pg"));
        // kind 走 Integer code 双向（BaseEnum 编解码，生产同配置）
        verify(appService).create(argThat(cmd -> cmd.kindOrDefault() == EnvKind.DEV));
    }

    @Test
    void given_existing_workspace_when_get_then_returned() throws Exception {
        when(appService.get("100")).thenReturn(new WorkspaceResponse("100", EnvKind.DEV, "开发",
                "ws-100-dev", "net-100", 20000, 20001, ProvisioningStatus.READY, "就绪", null,
                List.of(), null));

        performAsUser(get("/api/workspaces/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value("100"))
                .andExpect(jsonPath("$.data.networkName").value("net-100"));
    }

    @Test
    void given_unknown_workspace_when_get_then_wsp_001_mapped_to_404() throws Exception {
        when(appService.get("404"))
                .thenThrow(new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND));

        performAsUser(get("/api/workspaces/404"))
                .andExpect(status().isNotFound())
                // 统一信封：code = HTTP 语义状态，message = 错误文案（WSP_001 前缀在 WorkspaceMessage 注册）
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("工作区不存在"));
    }

    @Test
    void given_failed_workspace_when_retry_then_provisioning_response_returned() throws Exception {
        when(appService.retry("100")).thenReturn(new WorkspaceResponse("100", EnvKind.DEV, "开发",
                "ws-100-dev", "net-100", 0, 0, ProvisioningStatus.PROVISIONING, "置备中", null,
                List.of(), null));

        performAsUser(post("/api/workspaces/100/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value("100"))
                .andExpect(jsonPath("$.data.status").value(1)); // PROVISIONING（Integer code）
        verify(appService).retry("100");
    }

    @Test
    void given_non_failed_workspace_when_retry_then_wsp_009_mapped_to_400() throws Exception {
        when(appService.retry("100"))
                .thenThrow(new ApplicationException(WorkspaceMessage.WORKSPACE_STATE_INVALID));

        performAsUser(post("/api/workspaces/100/retry"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("工作区置备状态不合法"));
    }

    @Test
    void given_command_when_exec_then_result_returned() throws Exception {
        when(appService.exec(eq("100"), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("hi", "", 0));

        performAsUser(post("/api/workspaces/100/exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"echo hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stdout").value("hi"))
                .andExpect(jsonPath("$.data.exitCode").value(0));
    }

    @Test
    void given_blank_command_when_exec_then_rejected_as_400() throws Exception {
        performAsUser(post("/api/workspaces/100/exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void given_workspace_when_destroy_then_ok_without_data() throws Exception {
        performAsUser(delete("/api/workspaces/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
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
