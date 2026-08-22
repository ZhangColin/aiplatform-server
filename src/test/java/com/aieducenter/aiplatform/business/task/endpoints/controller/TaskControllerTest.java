package com.aieducenter.aiplatform.business.task.endpoints.controller;

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
import com.cartisan.web.config.JacksonConfiguration;
import com.cartisan.web.exception.GlobalExceptionHandler;

import com.aieducenter.aiplatform.business.task.application.FixDispatchAppService;
import com.aieducenter.aiplatform.business.task.application.TaskLifecycleAppService;
import com.aieducenter.aiplatform.business.task.application.TaskQueryAppService;
import com.aieducenter.aiplatform.business.task.application.dto.command.CreateTaskCommand;
import com.aieducenter.aiplatform.business.task.application.dto.command.SubmitTaskCommand;
import com.aieducenter.aiplatform.business.task.application.dto.response.BugResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskCardResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskDetailResponse;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskResponse;
import com.aieducenter.aiplatform.business.task.domain.enums.BugSeverity;
import com.aieducenter.aiplatform.business.task.domain.enums.BugStatus;
import com.aieducenter.aiplatform.business.task.domain.enums.TaskStatus;
import com.aieducenter.aiplatform.business.task.domain.enums.TaskType;
import com.aieducenter.aiplatform.business.task.domain.error.TaskMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 REST 面（swagger 契约验收，A4 §6）：统一信封、枚举 Integer code 双向
 * （severity/status）、建/列表/详情/start/submit/confirm/reject/cancel 端点
 * 归位、TASK_007 → 404、载荷二选一校验经 bean validation 与应用层双层。
 */
@WebMvcTest({TaskController.class, ProjectTaskController.class, ProjectBugController.class})
@Import({TaskControllerTest.ExceptionAdviceConfig.class, JacksonConfiguration.class})
class TaskControllerTest {

    private static final Long ME = 4242L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskLifecycleAppService lifecycleAppService;

    @MockitoBean
    private TaskQueryAppService queryAppService;

    @MockitoBean
    private FixDispatchAppService fixDispatchAppService;

    /** A2 起全 /api/** 拦截——MVC 契约测试不走登录链，夹具直接注 RequestContext。 */
    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, ME, "task-rest-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_valid_command_when_create_task_then_wrapped_envelope() throws Exception {
        when(lifecycleAppService.create(eq("9001"), any())).thenReturn(taskResponse("t1"));

        performAsUser(post("/api/projects/9001/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "回归测试", "content": "全量回归", "assigneeAccountId": 4243}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value("t1"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.statusName").value("已发布"));

        verify(lifecycleAppService).create(eq("9001"), argThat(command ->
                command instanceof CreateTaskCommand record
                        && "回归测试".equals(record.title())
                        && Long.valueOf(4243L).equals(record.assigneeAccountId())));
    }

    @Test
    void given_blank_title_when_create_task_then_400_envelope() throws Exception {
        performAsUser(post("/api/projects/9001/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": " ", "content": "内容", "assigneeAccountId": 4243}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(lifecycleAppService, never()).create(anyString(), any());
    }

    @Test
    void given_project_tasks_when_list_then_envelope_array() throws Exception {
        when(queryAppService.listByProject("9001")).thenReturn(List.of(taskResponse("t1")));

        performAsUser(get("/api/projects/9001/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].taskId").value("t1"))
                .andExpect(jsonPath("$.data[0].rejectReason").value(nullValue()));
    }

    @Test
    void given_my_tasks_when_list_then_cards_with_project_brief() throws Exception {
        when(queryAppService.myTasks()).thenReturn(List.of(new TaskCardResponse("t1", "9001",
                new TaskCardResponse.ProjectBrief("官网项目", "http://localhost:30080/"),
                "回归测试", "全量回归", TaskStatus.PUBLISHED, "已发布",
                null, null, LocalDateTime.of(2026, 8, 22, 8, 0))));

        performAsUser(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].taskId").value("t1"))
                .andExpect(jsonPath("$.data[0].project.name").value("官网项目"))
                .andExpect(jsonPath("$.data[0].project.previewUrl").value("http://localhost:30080/"))
                .andExpect(jsonPath("$.data[0].status").value(1));
    }

    @Test
    void given_task_detail_when_get_then_bugs_carried() throws Exception {
        when(queryAppService.detail(7700L)).thenReturn(new TaskDetailResponse(
                taskResponse("t1"),
                new TaskCardResponse.ProjectBrief("官网项目", null),
                List.of(new BugResponse("b1", "9001", "t1", "登录 500", "描述", "步骤",
                        BugSeverity.CRITICAL, "严重", BugStatus.OPEN, "待修复",
                        null, null, null, LocalDateTime.of(2026, 8, 22, 8, 0),
                        LocalDateTime.of(2026, 8, 22, 8, 0)))));

        performAsUser(get("/api/tasks/7700"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.taskId").value("t1"))
                .andExpect(jsonPath("$.data.bugs[0].bugId").value("b1"))
                .andExpect(jsonPath("$.data.bugs[0].severity").value(2))
                .andExpect(jsonPath("$.data.bugs[0].severityName").value("严重"));
    }

    @Test
    void given_first_round_payload_when_submit_then_severity_code_and_bugs_shape() throws Exception {
        when(lifecycleAppService.submit(eq(7700L), any())).thenReturn(taskResponse("t1"));

        performAsUser(post("/api/tasks/7700/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"report": "首轮报告", "bugs": [
                                  {"title": "登录 500", "description": "提交后 500",
                                   "reproSteps": "1. 打开登录页", "severity": 2}
                                ]}
                                """))
                .andExpect(status().isOk());

        verify(lifecycleAppService).submit(eq(7700L), argThat(command ->
                command instanceof SubmitTaskCommand payload
                        && payload.bugs() != null
                        && payload.bugs().size() == 1
                        && payload.bugs().get(0).severity() == BugSeverity.CRITICAL // Integer code 双向
                        && payload.results() == null));
    }

    @Test
    void given_retest_payload_when_submit_then_results_shape() throws Exception {
        when(lifecycleAppService.submit(eq(7700L), any())).thenReturn(taskResponse("t1"));

        performAsUser(post("/api/tasks/7700/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"report": "复测报告", "results": [{"bugId": 8800, "pass": true, "note": "已修复"}]}
                                """))
                .andExpect(status().isOk());

        verify(lifecycleAppService).submit(eq(7700L), argThat(command ->
                command instanceof SubmitTaskCommand payload
                        && payload.bugs() == null
                        && payload.results().get(0).bugId() == 8800L
                        && payload.results().get(0).pass()));
    }

    @Test
    void given_missing_report_when_submit_then_400() throws Exception {
        performAsUser(post("/api/tasks/7700/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bugs": []}
                                """))
                .andExpect(status().isBadRequest());

        verify(lifecycleAppService, never()).submit(anyLong(), any());
    }

    @Test
    void given_action_endpoints_when_post_then_delegated_with_parse() throws Exception {
        when(lifecycleAppService.start(7700L)).thenReturn(taskResponse("t1"));
        when(lifecycleAppService.confirm(7700L)).thenReturn(new TaskDetailResponse(
                taskResponse("t1"), null, List.of()));
        when(lifecycleAppService.reject(eq(7700L), any())).thenReturn(taskResponse("t1"));
        when(lifecycleAppService.cancel(7700L)).thenReturn(taskResponse("t1"));

        performAsUser(post("/api/tasks/7700/start")).andExpect(status().isOk());
        performAsUser(post("/api/tasks/7700/confirm")).andExpect(status().isOk());
        performAsUser(post("/api/tasks/7700/cancel")).andExpect(status().isOk());
        performAsUser(post("/api/tasks/7700/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"缺登录用例\"}"))
                .andExpect(status().isOk());

        verify(lifecycleAppService).start(7700L);
        verify(lifecycleAppService).confirm(7700L);
        verify(lifecycleAppService).cancel(7700L);
        verify(lifecycleAppService).reject(7700L, "缺登录用例");
    }

    @Test
    void given_blank_reason_when_reject_then_400() throws Exception {
        performAsUser(post("/api/tasks/7700/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));

        verify(lifecycleAppService, never()).reject(anyLong(), any());
    }

    @Test
    void given_missing_task_when_any_endpoint_then_404_task_007_envelope() throws Exception {
        when(lifecycleAppService.start(-1L))
                .thenThrow(new ApplicationException(TaskMessage.TASK_NOT_FOUND));

        performAsUser(post("/api/tasks/-1/start"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(TaskMessage.TASK_NOT_FOUND.message()));

        // 非数值路径段同 404（寻址解析收口）
        performAsUser(get("/api/tasks/abc"))
                .andExpect(status().isNotFound());
    }

    @Test
    void given_project_bugs_when_list_then_envelope() throws Exception {
        when(queryAppService.bugs("9001")).thenReturn(List.of(new BugResponse("b1", "9001",
                "t1", "登录 500", null, null, BugSeverity.CRITICAL, "严重",
                BugStatus.OPEN, "待修复", null, null, null,
                LocalDateTime.of(2026, 8, 22, 8, 0), LocalDateTime.of(2026, 8, 22, 8, 0))));

        performAsUser(get("/api/projects/9001/bugs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].bugId").value("b1"))
                .andExpect(jsonPath("$.data[0].fixRunId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].closedReason").value(nullValue()));
    }

    // ---------- 修复派发与手工关闭（A4 §4/#27） ----------

    @Test
    void given_project_bugs_when_dispatch_fixes_then_void_envelope_and_delegated()
            throws Exception {
        performAsUser(post("/api/projects/9001/bugs/dispatch-fixes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(fixDispatchAppService).dispatchFixes("9001");
    }

    @Test
    void given_reason_when_close_bug_then_verified_envelope() throws Exception {
        when(lifecycleAppService.closeBug(eq("9001"), eq(55L), eq("需求如此，非缺陷")))
                .thenReturn(new BugResponse("b1", "9001", "t1", "未重现", null, null,
                        BugSeverity.MINOR, "轻微", BugStatus.VERIFIED, "复测通过",
                        null, null, "需求如此，非缺陷",
                        LocalDateTime.of(2026, 8, 22, 8, 0), LocalDateTime.of(2026, 8, 22, 8, 0)));

        performAsUser(post("/api/projects/9001/bugs/55/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"需求如此，非缺陷\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(3))
                .andExpect(jsonPath("$.data.statusName").value("复测通过"))
                .andExpect(jsonPath("$.data.closedReason").value("需求如此，非缺陷"));

        verify(lifecycleAppService).closeBug("9001", 55L, "需求如此，非缺陷");
    }

    @Test
    void given_blank_reason_when_close_bug_then_400_envelope() throws Exception {
        performAsUser(post("/api/projects/9001/bugs/55/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \" \"}"))
                .andExpect(status().isBadRequest());

        verify(lifecycleAppService, never()).closeBug(anyString(), anyLong(), anyString());
    }

    // ---------- 测试数据 ----------

    private static TaskResponse taskResponse(String taskId) {
        return new TaskResponse(taskId, "9001", TaskType.TEST, "测试任务", "回归测试", "全量回归",
                4243L, "外包测试", TaskStatus.PUBLISHED, "已发布", null,
                null, null, null, null, LocalDateTime.of(2026, 8, 22, 8, 0),
                LocalDateTime.of(2026, 8, 22, 8, 0));
    }

    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
