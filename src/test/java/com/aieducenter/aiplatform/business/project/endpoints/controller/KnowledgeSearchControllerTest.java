package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import com.cartisan.core.context.RequestContext;
import com.cartisan.web.exception.GlobalExceptionHandler;

import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.business.project.application.ProjectKnowledgeAppService;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识检索演示端点（A5 §4 契约面）：GET /api/knowledge/search?q=&topK= ——
 * ApiResponse 信封、q/topK 透传、命中条目四键（kind/projectName/title/snippet）。
 * 检索语义与降级见 ProjectKnowledgeAppServiceTest / KnowledgeAppServiceTest。
 */
@WebMvcTest(KnowledgeSearchController.class)
@Import({KnowledgeSearchControllerTest.ExceptionAdviceConfig.class,
        com.cartisan.web.config.JacksonConfiguration.class})
class KnowledgeSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectKnowledgeAppService knowledgeAppService;

    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 1L, "knowledge-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_query_when_search_then_wrapped_hits_with_four_fields() throws Exception {
        when(knowledgeAppService.retrieve("电商", 3)).thenReturn(List.of(
                new KnowledgeHit("QA", "第一单", "用哪个框架?", "问：…\n答：React")));

        performAsUser(get("/api/knowledge/search").param("q", "电商").param("topK", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].kind").value("QA"))
                .andExpect(jsonPath("$.data[0].projectName").value("第一单"))
                .andExpect(jsonPath("$.data[0].title").value("用哪个框架?"))
                .andExpect(jsonPath("$.data[0].snippet").value("问：…\n答：React"));
        verify(knowledgeAppService).retrieve("电商", 3);
    }

    @Test
    void given_query_without_topk_when_search_then_null_passthrough() throws Exception {
        when(knowledgeAppService.retrieve(eq("登录异常"), eq(null))).thenReturn(List.of());

        performAsUser(get("/api/knowledge/search").param("q", "登录异常"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        verify(knowledgeAppService).retrieve("登录异常", null); // 缺省解析归应用层（配置值）
    }

    @Test
    void given_missing_q_when_search_then_400() throws Exception {
        performAsUser(get("/api/knowledge/search"))
                .andExpect(status().isBadRequest());
    }

    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
