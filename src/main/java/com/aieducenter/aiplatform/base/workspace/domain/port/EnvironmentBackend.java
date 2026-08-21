package com.aieducenter.aiplatform.base.workspace.domain.port;

import java.net.URI;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;

/**
 * 环境后端端口（CONTEXT.md「环境」六条能力面的 Phase A 子集）：薄接口只归一化，
 * 不塞业务语义。后端可替换：本地 Docker（Docker CLI 子进程）→ 上云 TKE/远端
 * （B0 蓝图 §3 演化路径，配置切换适配器，接口不动）。
 *
 * <p>本片实现四条：createWorkspace（含项目专属 network + pg/redis 中间件供给与
 * {@code /workspace/.env} 连接串注入）/ destroyWorkspace（容器→网络→卷级联清理）/
 * exec（容器内跑命令取结果）/ exposePort（预览 URL）。snapshot+restore、
 * attachResource 按需随各自切片扩。</p>
 */
@Port(PortType.CLIENT)
public interface EnvironmentBackend {

    /** dev 镜像内置静态预览服务器监听的容器端口（镜像与应用约定的单一事实）。 */
    int DEV_PREVIEW_CONTAINER_PORT = 8081;

    /**
     * dev 容器内引擎接入点（opencode serve）监听的容器端口（镜像与后端约定的单一
     * 事实；片2 agentengine 消费——宿主映射 hostPort 在工作区句柄上）。
     */
    int DEV_ENGINE_CONTAINER_PORT = 4096;

    /**
     * 创建工作区并落定全部真实副作用（容器/网络/中间件/.env），返回句柄与资源清单。
     * 幂等倾向：对同名残留先清理再建。
     */
    WorkspaceProvision createWorkspace(WorkspaceId workspaceId, EnvKind kind);

    /**
     * 销毁工作区：级联清理容器 → 网络 → 数据卷（尽力而为，失败不抛——记录清理由调用方负责）。
     */
    void destroyWorkspace(WorkspaceHandle handle);

    /**
     * 在工作区内执行一条命令，取 stdout/stderr/exitCode。
     */
    ExecResult exec(WorkspaceHandle handle, String command);

    /**
     * 暴露容器端口为可访问的预览 URL（本地 = Docker 端口映射；线上 = Ingress/负载均衡）。
     */
    URI exposePort(WorkspaceHandle handle, int containerPort);

    /**
     * 打包工作区源码为 tar.gz 字节流（「取走工作区内容」的能力面：调用方拿去做
     * 下载交付，业务语义归调用方）。实现负责排除平台生成的机密
     * （/workspace/.env 连接串）与可重建的重物（node_modules）——包里是源码
     * 事实，不是环境镜像。
     */
    byte[] packSource(WorkspaceHandle handle);
}
