package com.aieducenter.aiplatform.base.workspace.application.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.cartisan.web.mapper.DomainMapper;

import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.entity.MiddlewareResource;

/**
 * 工作区 → 响应 DTO 映射（编写规范 §4.3：Mapper 继承 DomainMapper）。
 * workspaceId 取主键数值形 → 字符串形；kindName/kindCode 取 BaseEnum 属性。
 */
@Mapper(componentModel = "spring")
public interface WorkspaceMapper extends DomainMapper<Workspace, WorkspaceResponse> {

    @Override
    @Mapping(target = "workspaceId", source = "id")
    @Mapping(target = "kindName", source = "kind.name")
    @Mapping(target = "statusName", source = "status.name")
    WorkspaceResponse convert(Workspace workspace);

    @Mapping(target = "kindName", source = "kind.name")
    @Mapping(target = "url", source = "internalUrl")
    WorkspaceResponse.MiddlewareResourceResponse convert(MiddlewareResource resource);
}
