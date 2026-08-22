package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

/**
 * 项目响应（列表/详情共用；主链定义数据与门就绪归片5c 项目详情，票 #24）。
 *
 * @param id            项目标识（TSID 十进制字符串）
 * @param name          项目名
 * @param type          项目类型（code）
 * @param typeName      项目类型名
 * @param engine        开发智能体引擎（注册表键）
 * @param workspaceId   dev 工作区标识（exec/会话寻址锚点）
 * @param stage         期当前阶段名（无期 = 空）
 * @param stageLabel    阶段展示标签
 * @param status        派生项目状态（code）：IN_PROGRESS（有 OPEN 期）/ DELIVERED
 * @param statusName    派生状态名
 * @param stageTaskCount 当前阶段任务计数（门禁输入）
 * @param archived      是否已归档（单向终点，动作归片5c）
 * @param createdAt     创建时间
 */
public record ProjectResponse(
        String id,
        String name,
        ProjectType type,
        String typeName,
        String engine,
        String workspaceId,
        String stage,
        String stageLabel,
        ProjectStatus status,
        String statusName,
        Integer stageTaskCount,
        Boolean archived,
        LocalDateTime createdAt
) {
}
