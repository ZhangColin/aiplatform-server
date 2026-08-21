/**
 * Workspace Context（base.workspace）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>环境抽象（EnvironmentBackend 端口）+ Docker CLI 后端</li>
 *   <li>中间件资源供给（network / pg / redis 随环境，.env 注入）</li>
 *   <li>工作区落库（wsp_workspaces / wsp_resources，服务重启后接回）</li>
 *   <li>生命周期应用事件发布端（WorkspaceCreated / WorkspaceDestroyed / PreviewReady，
 *       PUBLISHER 端口 AFTER_COMMIT，A1 §4）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>底座环境能力，零业务概念——不知道 projectId，生命周期事件由本上下文发布、
 * 业务侧消费。表前缀 {@code wsp_}，错误码前缀 {@code WSP_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：聚合根（Workspace）、实体（MiddlewareResource）、值对象
 *       （WorkspaceId/WorkspaceHandle/ExecResult/ProvisionedResource/WorkspaceProvision）、
 *       枚举（EnvKind/MiddlewareKind）、端口（EnvironmentBackend）、错误、仓储接口</li>
 *   <li>application - 应用层：WorkspaceLifecycleAppService、生命周期应用事件、DTO</li>
 *   <li>infrastructure - 基础设施层：docker/（Docker CLI 适配器）</li>
 *   <li>endpoints - 北向接口层：controller/（最小 REST 面：创建/查询/exec/销毁）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Workspace", subDomain = SubDomain.SUPPORTING)
package com.aieducenter.aiplatform.base.workspace;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
