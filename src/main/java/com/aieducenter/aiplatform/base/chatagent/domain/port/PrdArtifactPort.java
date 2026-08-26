package com.aieducenter.aiplatform.base.chatagent.domain.port;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

/**
 * PRD 产物的业务契约（#49）：base.chatagent 提供 savePrd 工具机制（对话智能体
 * 判定需求明确后经它落 PRD 到项目工作区），产物语义归业务——落盘路径的正本与
 * 落盘成功后的业务效果（置项目级「PRD 已产出」状态位 + 发 document-updated
 * 事件）经本端口取用/回调。端口在消费方（base.chatagent），实现归事实持有方
 * business.project（照 OpenBugQueryPort 的跨上下文先例）。
 */
@Port(PortType.CLIENT)
public interface PrdArtifactPort {

    /**
     * PRD 在项目工作区内的路径（相对工作区根 {@code /workspace}，如
     * {@code docs/PRD.md}；业务正本，PRD 读端点同源）。
     */
    String workspacePath();

    /**
     * PRD 已写出落盘（文件已在项目工作区，含修订覆盖）：置「PRD 已产出」状态位 +
     * 发 document-updated 事件（SSE 事务提交后发射制）。失败抛异常——工具回失败
     * 结果（模型可见可重试，文件写幂等覆盖，重试只前进）。
     *
     * @param workspaceId 工作区标识（savePrd 工具注册时锚定的项目 dev 工作区）
     */
    void onWritten(String workspaceId);
}
