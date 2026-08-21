package com.aieducenter.aiplatform.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 404 语义恢复验证：静态资源未命中（NoResourceFoundException）映射 404 而非
 * 落进兜底变 500（cartisan 只映射了旧机制 NoHandlerFoundException）。
 */
class NotFoundExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new NotFoundExceptionHandler())
                .build();
    }

    @RestController
    static class ProbeController {

        @GetMapping("/test/not-found")
        public void notFound() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/test/not-found");
        }
    }

    @Test
    void given_no_resource_found_when_handle_then_404_with_unified_envelope() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }
}
