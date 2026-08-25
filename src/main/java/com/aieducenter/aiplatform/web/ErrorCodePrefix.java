package com.aieducenter.aiplatform.web;

/**
 * 错误码前缀注册表（ADR-0001：错误码格式 {@code {CONTEXT}_{NNN}}）。
 *
 * <p>一个 BC 一个前缀，新 BC 建立即在此注册（eventhub / process / workbench 无
 * REST 错误面，不设前缀）。通用 HTTP 错误复用 {@link com.cartisan.core.exception.BaseCodeMessage}，
 * 不进本表。各 BC 错误枚举（{@code XxxMessage implements CodeMessage}）的 code
 * 必须以本表登记的前缀开头。</p>
 */
public enum ErrorCodePrefix {

    /** base.workspace：环境 / 工作区（wsp_） */
    WSP("WSP_", "base.workspace"),

    /** base.agentengine：智能体引擎（agt_） */
    AGT("AGT_", "base.agentengine"),

    /** base.knowledge：知识库（knw_） */
    KNW("KNW_", "base.knowledge"),

    /** base.metering：计量（met_） */
    METER("METER_", "base.metering"),

    /** base.chatagent：对话智能体（chat_，#44 引入、REST 面归 #45） */
    CHAT("CHAT_", "base.chatagent"),

    /** business.project：项目主链（prj_） */
    PRJ("PRJ_", "business.project"),

    /** business.identity：账号认证（idn_） */
    IDN("IDN_", "business.identity"),

    /** business.task：任务系统（tsk_，A4 落码） */
    TASK("TASK_", "business.task");

    private final String prefix;
    private final String boundedContext;

    ErrorCodePrefix(String prefix, String boundedContext) {
        this.prefix = prefix;
        this.boundedContext = boundedContext;
    }

    public String prefix() {
        return prefix;
    }

    /**
     * 所属限界上下文（base./business. 下的 BC 包名，非全限定）。
     */
    public String boundedContext() {
        return boundedContext;
    }
}
