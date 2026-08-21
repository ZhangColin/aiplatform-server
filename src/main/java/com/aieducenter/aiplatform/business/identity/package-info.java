/**
 * Identity Context（business.identity）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>账号档案（首次登录按外部 ID 自动建档，无角色概念）</li>
 *   <li>OIDC BFF 认证（/auth/login、/auth/callback、/auth/logout + /api/me；
 *       自管内存会话，token 只在服务端）与会话 → RequestContext 过滤器</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>认证以 identity 服务为正主（照 aieducenter-identity/demo 形态）；不引入
 * cartisan-security，401/403 全局映射占位见
 * {@code com.aieducenter.aiplatform.web.AuthExceptionHandler}（随本上下文接线）。
 * 表前缀 {@code idn_}，错误码前缀 {@code IDN_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>四层骨架（domain / application / infrastructure / endpoints）随 A2 落位</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Identity", subDomain = SubDomain.SUPPORTING)
package com.aieducenter.aiplatform.business.identity;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
