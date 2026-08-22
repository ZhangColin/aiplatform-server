package com.aieducenter.aiplatform.business.identity.application.dto.response;

/**
 * 账号响应（指派下拉条目）：accountId 为 TSID 十进制字符串（REST 寻址口径）。
 */
public record AccountResponse(
        String accountId,
        String displayName
) {
}
