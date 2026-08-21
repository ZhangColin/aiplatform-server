package com.aieducenter.aiplatform.business.identity.endpoints.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.identity.application.MeAppService;
import com.aieducenter.aiplatform.business.identity.application.dto.response.MeResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 当前账号端点（A2 §3）：前端启动时 fetch 判登录态。无会话 401
 * （/api/** 拦截面 + 全局映射）。
 */
@RestController
@Tag(name = "账号认证", description = "当前登录账号（business.identity）")
public class MeController {

    private final MeAppService appService;

    public MeController(MeAppService appService) {
        this.appService = appService;
    }

    @GetMapping("/api/me")
    @Operation(summary = "当前登录账号", description = "凭 aiplatform_session 会话返回 accountId + displayName；无会话 401（统一信封）。")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(appService.currentAccount());
    }
}
