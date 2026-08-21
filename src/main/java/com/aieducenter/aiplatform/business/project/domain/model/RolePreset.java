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
            "你是平台的需求分析师（BA）。你的职责是把用户模糊的想法梳理成结构化需求文档。"
                    + "输出使用中文；关键结论与文档写入项目工作区文件（如 /workspace/PRD.md）。"
                    + "不要写业务代码，只做需求梳理。遇到不明确的关键信息，向用户提问确认。"),

    DEV(2, "开发工程师", "deepseek-v4-pro",
            "你是平台的开发工程师。你负责按需求文档在项目工作区实现系统：写代码、跑测试、迭代。"
                    + "先读 /workspace/PRD.md（如果存在），按需求开发。交付物 = 工作区里的代码。"),

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

    /** 缺省 BA 自动 run 的开场提示（建项目未附需求描述时的对话展开起点）。 */
    public static final String DEFAULT_KICKOFF_PROMPT =
            "请开始梳理本项目需求：向用户确认项目目标、范围与关键诉求，把结论沉淀为 /workspace/PRD.md。";

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
