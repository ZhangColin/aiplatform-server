package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.aieducenter.aiplatform.base.process.domain.model.ExitGate;
import com.aieducenter.aiplatform.base.process.domain.model.MainChainDefinition;
import com.aieducenter.aiplatform.base.process.domain.model.StageEntry;

import com.aieducenter.aiplatform.business.project.domain.enums.ConfirmationKind;

/**
 * 平台主链定义（A3 §2.2，唯一一条——「模板」概念退役，过程演化 = 业务代码演化）：
 * 业务侧代码定义传入 base.process（引擎无表、不知业务，只管推进/驳回停留/门禁计数）。
 *
 * <p>序列与门（A3 §2.2/§2.4）：需求梳理(BA) →〔需求确认·用户〕→ Demo(DEMO) →
 * 〔Demo 确认·用户〕→ 开发(DEV) → 测试(无默认角色) →〔开发完成确认·开发平台〕→
 * 验收(无默认角色) →〔验收·用户〕→ 关闭(终态)。开发段无出口门——开发→测试由
 * 编排触发（A4 首个测试任务创建时 advance）；验收门 minTasks=0（验收段无
 * agent 任务）；产物清单只作沉淀范围不作门禁（v1 仅需求梳理段 docs/PRD.md）。
 * ARCH preset 保留但不设段（开发期间手动下任务）。</p>
 */
public final class ProjectMainChain {

    // ---------- 阶段名稳定键（期聚合存储 / SSE payload / REST 寻址共用的唯一真值） ----------

    /** 需求梳理（建项目即自动跑 BA 的前缀段）。 */
    public static final String STAGE_BA = "BA";

    /** Demo 原型段（G1 通过自动跑 DEMO，完事 preview-ready）。 */
    public static final String STAGE_DEMO = "DEMO";

    /** 开发段（开发起全手动，Replit 式工作台）。 */
    public static final String STAGE_DEV = "DEV";

    /** 测试段（无默认角色，OPC 外包，A4 接线）。 */
    public static final String STAGE_TEST = "TEST";

    /** 验收段（无默认角色；门 minTasks=0）。 */
    public static final String STAGE_ACCEPTANCE = "ACCEPTANCE";

    /** 终态（验收门通过联动收口）。 */
    public static final String STAGE_CLOSED = "CLOSED";

    /** 出口门 actor 稳定键（A3 §2.2：用户三扇 + 开发平台一扇）。 */
    public static final String GATE_ACTOR_USER = "USER";

    /** 开发完成确认门的 actor（开发平台拍板，A3 §2.4 业务谓词归 A4 接线）。 */
    public static final String GATE_ACTOR_PLATFORM = "PLATFORM";

    /**
     * BA 段产物 PRD 在工作区内的路径（相对 {@code /workspace}，#41 grilling 定案）：
     * PRD = 工作区文件事实源，读写方（PRD 读端点 / A5 ARTIFACT 摄取 / BA savePrd 与
     * 编码智能体的直读）共用此一事实，勿散落字面量。
     */
    public static final String PRD_ARTIFACT = "docs/PRD.md";

    private static final MainChainDefinition DEFINITION = new MainChainDefinition(List.of(
            StageEntry.of(STAGE_BA, "需求梳理", RolePreset.BA.name(), List.of(PRD_ARTIFACT),
                    new ExitGate(GATE_ACTOR_USER, 1)),
            StageEntry.of(STAGE_DEMO, "Demo", RolePreset.DEMO.name(), null,
                    new ExitGate(GATE_ACTOR_USER, 1)),
            // 开发段无门：开发→测试由编排触发（A4 首个测试任务），引擎不校验计数
            StageEntry.of(STAGE_DEV, "开发", RolePreset.DEV.name(), null, null),
            StageEntry.of(STAGE_TEST, "测试", null, null,
                    new ExitGate(GATE_ACTOR_PLATFORM, 1)),
            // 验收段无 agent 任务（minTasks=0），产物走任务确认挂钩不落工作区文件
            StageEntry.of(STAGE_ACCEPTANCE, "验收", null, null,
                    new ExitGate(GATE_ACTOR_USER, 0)),
            StageEntry.terminalOf(STAGE_CLOSED, "关闭")));

    private ProjectMainChain() {
    }

    /** 有门阶段 → 确认种类（A3 §3 四扇门；与上面的出口门定义同处一文件，加门改一处）。 */
    private static final Map<String, ConfirmationKind> GATE_KINDS = Map.of(
            STAGE_BA, ConfirmationKind.REQUIREMENT,
            STAGE_DEMO, ConfirmationKind.DEMO,
            STAGE_TEST, ConfirmationKind.DEVELOPMENT,
            STAGE_ACCEPTANCE, ConfirmationKind.ACCEPTANCE);

    /**
     * 主链定义（传入 base.process 的唯一实例；阶段推进引擎的推进/门禁语义归片5b 接线）。
     */
    public static MainChainDefinition definition() {
        return DEFINITION;
    }

    /**
     * 建项目的起始阶段（前缀段自动：BA）。
     */
    public static String firstStage() {
        return DEFINITION.first().name();
    }

    /**
     * 阶段出口门对应的确认种类（有门阶段与门一一对应；无门段/终态/空名返回空，
     * 由调用方按「当前阶段无确认门」拒绝）。
     */
    public static Optional<ConfirmationKind> confirmationKindOf(String stage) {
        return stage == null ? Optional.empty() : Optional.ofNullable(GATE_KINDS.get(stage));
    }
}
