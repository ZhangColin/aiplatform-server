/**
 * Identity Context（business.identity）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>账号档案（首次登录按外部 ID 自动建档，无角色概念）</li>
 *   <li>OIDC BFF 认证（/auth/login、/auth/callback、/auth/logout + /api/me；
 *       自管内存会话，token 只在服务端，id_token 第一天 JWKS RS256 验签）</li>
 *   <li>会话 → RequestContext 过滤器 + /api/** 鉴权拦截（全端点 401 语义）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>认证以 identity 服务为正主（照 aieducenter-identity/demo 形态）；不引入
 * cartisan-security，401/403 全局映射见
 * {@code com.aieducenter.aiplatform.web.AuthExceptionHandler}（本上下文的
 * ApiAuthInterceptor 抛出）。表前缀 {@code idn_}，错误码前缀 {@code IDN_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain：Account 聚合（自动建档）+ OauthTransaction / IdTokenClaims 值对象</li>
 *   <li>application：AuthAppService（登录/回调/登出编排）+ MeAppService（当前账号）</li>
 *   <li>infrastructure：oidc（SsoProperties / OidcClient / JWKS 验签）、
 *       session（内存会话存储 + RequestContext 过滤器）</li>
 *   <li>endpoints：AuthController / MeController + ApiAuthInterceptor</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Identity", subDomain = SubDomain.SUPPORTING)
package com.aieducenter.aiplatform.business.identity;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
