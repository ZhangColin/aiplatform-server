package com.aieducenter.aiplatform.web;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aieducenter.aiplatform.config.WebMvcConfig;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 片0 基线契约验证（ADR-0001）：分页统一 1 基（OneIndexedParameters）+
 * 全端点统一响应体（ApiResponse / PageResponse）。
 * 走 MVC 切片读 application.yml，验证的是配置生效，不是框架自测。
 * 切片只挂探针 controller（业务 controller 依赖各自 AppService，随其切片测）。
 */
@WebMvcTest(controllers = WebBaselineTest.BaselineProbeController.class)
@Import({WebBaselineTest.BaselineProbeController.class, WebMvcConfig.class})
class WebBaselineTest {

    @Autowired
    private MockMvc mockMvc;

    @RestController
    static class BaselineProbeController {

        private static final List<String> ALL_ITEMS =
                IntStream.rangeClosed(1, 5).mapToObj(i -> "item-0" + i).toList();

        @GetMapping("/test/baseline/pages")
        public PageResponse<String> pages(Pageable pageable) {
            List<String> items = ALL_ITEMS.stream()
                    .skip(pageable.getOffset())
                    .limit(pageable.getPageSize())
                    .toList();
            return new PageResponse<>(items, ALL_ITEMS.size(),
                    pageable.getPageNumber() + 1, pageable.getPageSize());
        }

        @GetMapping("/test/baseline/ok")
        public ApiResponse<String> ok() {
            return ApiResponse.ok("hello");
        }
    }

    @Test
    void given_one_indexed_pagination_when_request_page1_then_first_page_returned() throws Exception {
        // page=1 必须是第一页：若仍是 Spring 默认 0 基，page=1 会取到第二页（item-03 起）
        mockMvc.perform(get("/test/baseline/pages").queryParam("page", "1").queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0]").value("item-01"))
                .andExpect(jsonPath("$.items[1]").value("item-02"));
    }

    @Test
    void given_one_indexed_pagination_when_request_page2_then_second_page_returned() throws Exception {
        mockMvc.perform(get("/test/baseline/pages").queryParam("page", "2").queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items[0]").value("item-03"));
    }

    @Test
    void given_no_pagination_params_when_request_then_default_size_is_20() throws Exception {
        mockMvc.perform(get("/test/baseline/pages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items.length()").value(5));
    }

    @Test
    void given_api_response_endpoint_when_request_then_unified_envelope_returned() throws Exception {
        mockMvc.perform(get("/test/baseline/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.data").value("hello"));
    }

    @Test
    void given_swagger_ui_canonical_path_when_request_then_redirected_to_index_html() throws Exception {
        // ADR-0001 正本地址无后缀不命中静态资源，WebMvcConfig 重定向补齐
        mockMvc.perform(get("/swagger-ui/index"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
    }
}
