package com.aieducenter.aiplatform.base.process.domain.model;

import java.util.List;
import java.util.Optional;

/**
 * 主链定义（A3 §2.2）：平台业务过程唯一一条——「模板」概念退役，定义由业务侧
 * 代码写死后传入（唯一调用方 business.project，B0 蓝图 §2 片4）。
 *
 * <p>构造期校验不变量（fail fast——定义错误是编程错误，非运行期业务拒绝）：
 * 非空且至少一个实阶段 + 终态条目；终态唯一且居末位；阶段名全局唯一。
 * 引擎不知业务内容，只按条目序列推进。</p>
 *
 * @param stages 有序阶段条目（末位为主链终态 DONE）
 */
public record MainChainDefinition(List<StageEntry> stages) {

    public MainChainDefinition {
        if (stages == null || stages.size() < 2) {
            throw new IllegalArgumentException("主链定义至少需要一个实阶段与一个终态条目");
        }
        long terminals = stages.stream().filter(StageEntry::terminal).count();
        if (terminals == 0 || !stages.getLast().terminal()) {
            throw new IllegalArgumentException("主链末位必须是终态条目（DONE）");
        }
        if (terminals > 1) {
            throw new IllegalArgumentException("终态条目唯一且居末位，不得出现在中间");
        }
        long distinctNames = stages.stream().map(StageEntry::name).distinct().count();
        if (distinctNames != stages.size()) {
            throw new IllegalArgumentException("阶段名在主链定义内重复");
        }
        stages = List.copyOf(stages);
    }

    /**
     * 按稳定标识查阶段条目。
     */
    public Optional<StageEntry> find(String name) {
        return stages.stream()
                .filter(stage -> stage.name().equals(name))
                .findFirst();
    }

    /**
     * 首阶段（建链后初始位置）。
     */
    public StageEntry first() {
        return stages.get(0);
    }
}
