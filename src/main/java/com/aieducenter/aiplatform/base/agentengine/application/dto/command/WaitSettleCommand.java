package com.aieducenter.aiplatform.base.agentengine.application.dto.command;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 等待点答复命令（REST 面）：type 三选一（answer / permission / deferred），各型
 * 必填字段——answer 要 answers、permission 要 approve、deferred 无必填（note 可选）。
 * 域内映射 {@link com.aieducenter.aiplatform.base.agentengine.domain.model.WaitSettlement}。
 */
public record WaitSettleCommand(

        @NotBlank(message = "type 不能为空")
        String type,

        /** 问答回答（type=answer 必填）：按问题顺序，每项 = 选中标签列表。 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<List<String>> answers,

        /** 权限决策（type=permission 必填）：true 批准 / false 拒绝。 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean approve,

        /** 转任务备注（type=deferred 可选）：业务侧留痕，底座不解释。 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String note) {

    /** 答复型字面量（域内封闭集合的 REST 名）。 */
    public static final String TYPE_ANSWER = "answer";
    public static final String TYPE_PERMISSION = "permission";
    public static final String TYPE_DEFERRED = "deferred";
}
