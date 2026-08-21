/**
 * Workspace Context（base.workspace）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>环境抽象（EnvironmentBackend 端口）+ Docker 后端</li>
 *   <li>中间件资源供给（network / pg / redis 随环境，.env 注入）</li>
 *   <li>工作区落库（重启接回）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>底座环境能力，零业务概念——不知道 projectId，生命周期事件（WorkspaceCreated 等）
 * 由本上下文发布、业务侧消费。表前缀 {@code wsp_}，错误码前缀 {@code WSP_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>四层骨架（domain / application / infrastructure / endpoints）随片1 落位</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Workspace", subDomain = SubDomain.SUPPORTING)
package com.aieducenter.aiplatform.base.workspace;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
