package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

/**
 * 项目详情响应（片5c，A3 §5）：列表字段 + 主链定义数据（阶段序列，前端按数据
 * 渲染进度条——过程演化 UI 少改）+ 门就绪（计数门禁 ∧ 业务谓词，前端点亮按钮）。
 *
 * @param id             项目标识（TSID 十进制字符串）
 * @param name           项目名
 * @param type           项目类型（code）
 * @param typeName       项目类型名
 * @param engine         开发智能体引擎（注册表键）
 * @param workspaceId    dev 工作区标识
 * @param stage          期当前阶段名（无期 = 空）
 * @param stageLabel     阶段展示标签
 * @param status         派生项目状态（code）：IN_PROGRESS / DELIVERED / ARCHIVED（归档优先）
 * @param statusName     派生状态名
 * @param stageTaskCount 当前阶段任务计数（门禁输入；收口后不展示）
 * @param archived       是否已归档（单向终点）
 * @param createdAt      创建时间
 * @param stages         主链定义数据（阶段序列，含终态）
 * @param gate           当前阶段门就绪（无门段/已收口/无期 = null，无按钮可点亮）
 */
public record ProjectDetailResponse(
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
        LocalDateTime createdAt,
        List<StageView> stages,
        GateView gate
) {

    public ProjectDetailResponse {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    /**
     * 主链阶段条目视图（进度条渲染数据：序列 + 标签 + 终态标记 + 出口门拍板方）。
     *
     * @param name        阶段稳定键
     * @param label       展示标签
     * @param defaultRole 默认角色（可空——测试/验收无）
     * @param gateActor   出口门拍板方（可空——无门段的推进由编排触发）
     * @param terminal    是否主链终态（进度条终点标记）
     */
    public record StageView(String name, String label, String defaultRole,
                            String gateActor, boolean terminal) {
    }

    /**
     * 门就绪视图（A3 §5 {@code gate:{actor, ready}}）：ready = 引擎计数门禁
     * ∧ 业务谓词（G3 = 无未关闭 Bug）——approve 照此点亮，不满足只展示不强求。
     */
    public record GateView(String actor, boolean ready) {
    }
}
