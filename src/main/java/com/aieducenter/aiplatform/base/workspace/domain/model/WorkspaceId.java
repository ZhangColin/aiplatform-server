package com.aieducenter.aiplatform.base.workspace.domain.model;

import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.cartisan.core.exception.DomainException;

/**
 * 工作区标识（base.workspace 的中性寻址键，无 projectId——业务侧自行映射）。
 *
 * <p>TSID 数值形承载（与库主键同值，无 String/Long 往返），字符串形（容器/网络/卷
 * 命名、REST 路径段、事件载荷）经 {@link #value()} 派生——十进制数字，Docker/PG
 * 命名均安全。</p>
 */
public record WorkspaceId(long id) {

    public WorkspaceId {
        if (id <= 0) {
            throw new DomainException(WorkspaceMessage.WORKSPACE_ID_INVALID);
        }
    }

    /**
     * 生成新工作区标识（TSID）。
     */
    public static WorkspaceId generate() {
        return new WorkspaceId(TsidGenerator.newInstance().generate());
    }

    /**
     * 从字符串形解析（REST 路径段等入口）；非数值由调用方兜底（如 404）。
     */
    public static WorkspaceId of(String value) {
        return new WorkspaceId(Long.parseLong(value));
    }

    /**
     * 字符串形（容器/网络/卷命名、路径段、事件载荷共用）。
     */
    public String value() {
        return Long.toString(id);
    }

    @Override
    public String toString() {
        return value();
    }
}
