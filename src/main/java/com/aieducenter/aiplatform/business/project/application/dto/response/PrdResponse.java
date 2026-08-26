package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.Instant;

/**
 * PRD 读响应（#41）：当前版 PRD——markdown 正文 + 更新时间，v1 无版本链只最新版。
 * 事实源是项目 dev 工作区的 {@code docs/PRD.md}（本端点直读，读不到 = 未产出 404
 * PRJ_015，不在此载体表达）。
 *
 * @param projectId 项目标识（TSID 十进制字符串）
 * @param content   markdown 正文（工作区文件原样）
 * @param updatedAt 最近写出时间（工作区文件 mtime，秒精度——与正文同一事实源）
 */
public record PrdResponse(
        String projectId,
        String content,
        Instant updatedAt
) {
}
