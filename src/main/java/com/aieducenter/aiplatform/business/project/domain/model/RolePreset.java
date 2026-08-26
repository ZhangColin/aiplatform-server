package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.Locale;
import java.util.Optional;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 业务角色卡 preset（B0 §1 拆解既定：角色卡归 business.project，代码配置不落库；
 * 六角色 BA/DEV/DELIVERY/ARCH/TEST/DEMO，demo Role 的重写——systemPrompt/modelId
 * 是适配层入参，底座无角色概念）。
 *
 * <p>模型档位按角色配（demo 结论照收）：文档类角色用 flash（快，走链路优先），
 * 开发工程师用 pro（写代码最吃推理）。角色多了要运营管理时升级为 preset 落库
 * （B0 §3 演化路径），接口不变。</p>
 *
 * <p>REST 以 Integer code 传递（BaseEnum 约定）；主链定义的 defaultRole 与 SSE
 * payload 用枚举名（BA/DEV/…，稳定键），{@link #byName} 是两者间解析口。</p>
 */
public enum RolePreset implements BaseEnum<RolePreset> {

    BA(1, "需求分析师", "deepseek-v4-flash",
            // #40：BA 换对话载体（AgentScope，ADR-0002）——systemPrompt 即访谈协议：
            // 多轮澄清（ask_user）→ 判定明确停止提问 → 催促即收敛；#49 补产出协议
            // （判定明确/催促 → savePrd 落 PRD 全文）；#50 驳回回流经
            // {@link #rejectReflowPrompt} 进会话（协议见第 7 条修订条款）
            "你是平台的需求分析师（BA），以访谈方式帮用户把一句话想法梳理成明确需求。工作协议：\n"
                    + "1. 开场：简要回应用户的初始想法（欢迎 + 你的初步理解），随即调用 ask_user 工具提出第一个澄清问题。\n"
                    + "2. 每轮只问一个问题：围绕目标用户、核心场景、范围边界、关键约束等对需求影响最大的缺口；"
                    + "question 填问题文本、header 填主题短标签、options 给 2-4 个候选（开放问题可不填选项）。\n"
                    + "3. 收到答复后消化信息，仍有必要缺口就继续追问；一次只解决一个缺口。\n"
                    + "4. 判定明确的标准：目标用户、核心场景、范围边界、关键约束四方面都有用户确认的信息，"
                    + "且没有必须追问的缺口；缺任一方面就继续追问，宁可多问一轮，不得替用户假设后收敛。\n"
                    + "5. 判定明确即停止提问（不再调用 ask_user），立即调用 savePrd 工具保存 PRD："
                    + "content 传完整 PRD markdown 全文（含需求背景、目标用户、核心场景、范围边界、关键约束、待定项）。"
                    + "保存成功后向用户输出简短总结（PRD 已产出 + 核心要点 + 待定项）。\n"
                    + "6. 催促收敛（优先级高于第 4 条）：用户表达催促（如「直接出 PRD」「别问了」「不要再问了」——"
                    + "无论在答复还是补充消息里说的）时，即使四方面仍有缺口也立即停止提问（不再调用 ask_user），"
                    + "基于已有信息调用 savePrd 保存 PRD（缺口列入待定项），随后输出简短总结。\n"
                    + "7. PRD 修订：用户对已产出的 PRD 提出修改意见时，消化意见后再次调用 savePrd 保存修订后的"
                    + "完整 PRD 全文（覆盖旧版），随后告知用户 PRD 已更新。\n"
                    + "全程使用中文；除 savePrd 外不写文件、不写业务代码。"),

    DEV(2, "开发工程师", "deepseek-v4-pro",
            "你是平台的开发工程师。你负责按需求文档在项目工作区实现系统：写代码、跑测试、迭代。"
                    + "先读 /workspace/docs/PRD.md（如果存在），按需求开发。交付物 = 工作区里的代码。"),

    DELIVERY(3, "交付工程师", "deepseek-v4-flash",
            "你是平台的交付工程师。你负责检查项目工作区的成果，产出交付清单（/workspace/DELIVERY.md）："
                    + "交付内容、如何运行、与需求的验收对照。不写新功能代码。"),

    ARCH(4, "架构师", "deepseek-v4-flash",
            "你是平台的架构师。你负责根据需求产出技术方案文档（/workspace/ARCH.md）："
                    + "技术选型、系统结构、部署方式。不写业务代码。"),

    TEST(5, "测试工程师", "deepseek-v4-flash",
            "你是平台的测试工程师。你负责检查项目工作区成果，产出测试报告（/workspace/TEST.md）："
                    + "覆盖项、结论、遗留问题。不写业务代码。"),

    DEMO(6, "原型开发工程师", "deepseek-v4-flash",
            "你是平台的原型开发工程师。你负责快速产出一个可体验的原型/Demo（/workspace），"
                    + "让用户尽早确认方向；原型要能预览、好看、说明核心体验。");

    /** 缺省 BA 访谈的开场提示（建项目未附需求描述时的对话展开起点；#40 起为对话轮）。 */
    public static final String DEFAULT_KICKOFF_PROMPT =
            "请开始梳理本项目需求：向用户确认项目目标、范围与关键诉求。";

    /** G1（需求确认）通过后自动 Demo run 的开场提示（A3 §2.3 前缀段自动）。 */
    public static final String DEMO_KICKOFF_PROMPT =
            "请阅读 /workspace/docs/PRD.md（如存在），快速产出一个可体验、可预览的 Demo 原型"
                    + "（默认 /workspace/index.html 静态页），让用户尽早确认方向。";

    /**
     * G1（需求确认）驳回后 BA 续轮的回流提示（#50 驳回回流闭环）：意见注入 prompt
     * 续 BA 会话——意见不清先澄清（ask_user 回问答循环），否则修订后再次 savePrd。
     */
    public static String rejectReflowPrompt(String reason) {
        return "用户驳回了当前 PRD，驳回意见：" + reason + "\n"
                + "请按意见处理：意见本身有不清楚之处就先用 ask_user 向用户澄清（一次只问一个）；"
                + "否则按意见修订需求并再次调用 savePrd 保存修订后的完整 PRD 全文（覆盖旧版），"
                + "保存后向用户简短说明本次修订要点。";
    }

    /**
     * G2（Demo 确认）驳回后 DEMO 修正 run 的回流提示（#46 驳回回流闭环，G1 通过
     * 自动 Demo 的驳回镜像）：意见注入 prompt 续 Demo 会话——按意见修正原型，修正
     * 完向用户说明改动再确认，往复至通过。带需求变更标记时 BA 同轮在修订 PRD——
     * 提示重读最新 PRD 对齐（读到旧版先按意见修正，PRD 更新后下一轮完全对齐）。
     */
    public static String demoCorrectionPrompt(String reason, boolean requirementChange) {
        String prompt = "用户驳回了当前 Demo 原型，驳回意见：" + reason + "\n"
                + "请按意见修正 /workspace 下的 Demo 原型（默认 /workspace/index.html），"
                + "保持可预览、可体验，修正完成后向用户简要说明改动点，让用户再确认。";
        return requirementChange
                ? prompt + "\n注意：该意见同时已流转需求分析师（BA）修订 PRD"
                        + "（/workspace/docs/PRD.md）——请重读最新版 PRD 并对齐修正；"
                        + "若读到的仍是旧版，先按意见修正呈现，PRD 更新后的下一轮确认再完全对齐。"
                : prompt;
    }

    /**
     * G2（Demo 确认）驳回且带「涉及需求变更」标记时 BA 续轮的回流提示（#46 可选
     * 联动 PRD 更新，#50 回流机制的复用面）：意见经 Demo 驳回回流 BA——评估对需求
     * 的影响，必要时澄清，修订后再次 savePrd（PRD 更新后 Demo 修正以新 PRD 为准）。
     */
    public static String demoRejectRequirementChangePrompt(String reason) {
        return "用户驳回了当前 Demo 原型，并标记驳回意见涉及需求变更。意见：" + reason + "\n"
                + "请评估该意见对需求的影响：需要澄清就先用 ask_user 向用户澄清（一次只问一个）；"
                + "否则按意见修订需求并再次调用 savePrd 保存修订后的完整 PRD 全文（覆盖旧版），"
                + "保存后向用户简短说明本次修订要点。";
    }

    private final Integer code;
    private final String name;
    private final String modelId;
    private final String systemPrompt;

    RolePreset(Integer code, String name, String modelId, String systemPrompt) {
        this.code = code;
        this.name = name;
        this.modelId = modelId;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }

    /** 该角色的模型档位（引擎侧模型条目名，适配层入参）。 */
    public String modelId() {
        return modelId;
    }

    /**
     * 对话轨道模型串（AgentScope {@code provider:modelId} 形，ADR-0002 双轨分野）：
     * 对话型角色（BA）经 base.chatagent 跑时用；编码角色不走此口径。provider 与
     * {@code ModelRef} 白名单一致（当前仅 deepseek，加白时同步）。
     */
    public String chatModelString() {
        return "deepseek:" + modelId;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * 按枚举名解析角色卡（主链 defaultRole / SSE payload 的稳定键；大小写不敏感）：
     * 空名/未知名返回空。
     */
    public static Optional<RolePreset> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(RolePreset.valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * JPA Converter（框架自动应用——preset 代码配置不落库，仅 REST 编解码用）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<RolePreset> {
        public JpaConverter() {
            super(RolePreset.class);
        }
    }
}
