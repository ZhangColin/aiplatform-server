package com.aieducenter.aiplatform.business.identity.infrastructure.session;

import java.time.Instant;

/**
 * BFF 服务端会话（A2 §2 表 5）：token 三件套只存服务端，浏览器只持不透明
 * {@code aiplatform_session} cookie。
 *
 * <p>{@code accountId} 与显示名在 callback 建档时一并映射缓存——每请求还原
 * RequestContext 免查库。access/refresh token v1 不消费、expiresAt 不参与会话
 * 失效（会话生命周期 = 内存 Map 的存续：登出或进程重启即失）。</p>
 */
public record BffSession(
        Long accountId,
        String displayName,
        String idToken,
        String accessToken,
        String refreshToken,
        Instant expiresAt) {
}
