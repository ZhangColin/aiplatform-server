package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.project.domain.enums.DemandEntryKind;
import com.aieducenter.aiplatform.business.project.domain.enums.DemandSource;

/**
 * 需求池条目响应（新→旧；字段齐全面，A3 §4）。
 *
 * @param id        条目标识（TSID 十进制字符串）
 * @param content   收件内容
 * @param kind      条目类型（可空——未分类）
 * @param kindName  类型名（未分类为空）
 * @param source    来源
 * @param sourceName 来源名
 * @param createdBy 记录账号（无会话上下文为空）
 * @param createdAt 记录时间
 */
public record DemandPoolEntryResponse(
        String id,
        String content,
        DemandEntryKind kind,
        String kindName,
        DemandSource source,
        String sourceName,
        Long createdBy,
        LocalDateTime createdAt
) {
}
