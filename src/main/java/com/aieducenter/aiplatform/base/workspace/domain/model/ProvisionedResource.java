package com.aieducenter.aiplatform.base.workspace.domain.model;

import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;

/**
 * 随环境供给落定的一条中间件资源（createWorkspace 的产出之一，入库为 wsp_resources）：
 * 容器名（销毁级联与接回锚点）+ 宿主端口（本地工具直连）+ 容器网络内连接串
 * （/workspace/.env 注入原文，含凭据）。
 */
public record ProvisionedResource(
        MiddlewareKind kind,
        String containerName,
        int hostPort,
        String internalUrl) {
}
