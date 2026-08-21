package com.aieducenter.aiplatform.web;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 401/403 全局映射占位验证（ADR-0001：自写异常体系位，抛出方随 A2 identity 接线）。
 */
class AuthExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new AuthExceptionHandler(new ObjectMapper()))
                .build();
    }

    @RestController
    static class ProbeController {

        @GetMapping("/test/auth/unauthorized")
        public void unauthorized() {
            throw AuthException.unauthorized();
        }

        @GetMapping("/test/auth/forbidden")
        public void forbidden() {
            throw AuthException.forbidden();
        }
    }

    @Test
    void given_unauthorized_when_handle_then_401_with_unified_envelope() throws Exception {
        mockMvc.perform(get("/test/auth/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void given_forbidden_when_handle_then_403_with_unified_envelope() throws Exception {
        mockMvc.perform(get("/test/auth/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    void given_sse_accept_header_when_handle_then_401_not_500() throws Exception {
        // SSE 端点无会话：Accept: text/event-stream 下协商渲染不出 JSON 会 500——
        // 直接写响应保证 401 + 统一信封（A2 /api/events 拦截面）
        mockMvc.perform(get("/test/auth/unauthorized").accept("text/event-stream"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }
}
