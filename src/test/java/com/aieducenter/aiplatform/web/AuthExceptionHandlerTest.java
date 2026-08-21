package com.aieducenter.aiplatform.web;

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
                .setControllerAdvice(new AuthExceptionHandler())
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
}
