package com.aieducenter.aiplatform.business.identity.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前账号（A2 §3 最小契约：{ accountId, displayName }，email/picture 不进 v1）。
 */
@Schema(description = "当前登录账号")
public record MeResponse(
        @Schema(description = "账号 id（内部 TSID，字符串承载——超出 JS 安全整数范围）",
                example = "3897654321098765432")
        String accountId,
        @Schema(description = "显示名（nickname→name→preferred_username→sub 推导）", example = "张三")
        String displayName) {
}
