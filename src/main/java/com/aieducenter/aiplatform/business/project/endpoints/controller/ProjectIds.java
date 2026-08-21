package com.aieducenter.aiplatform.business.project.endpoints.controller;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * 项目 REST 寻址解析：projectId 路径段（TSID 十进制字符串）→ Long。
 * 非数值/非正数即不存在的标识，语义上同 404（与底座 parseId 口径一致）。
 */
final class ProjectIds {

    private ProjectIds() {
    }

    static Long parse(String projectId) {
        try {
            long parsed = Long.parseLong(projectId);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 非数值 → 落到下方统一 404
        }
        throw new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND);
    }
}
