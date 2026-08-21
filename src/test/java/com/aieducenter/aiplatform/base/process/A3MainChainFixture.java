package com.aieducenter.aiplatform.base.process;

import java.util.List;

import com.aieducenter.aiplatform.base.process.domain.model.ExitGate;
import com.aieducenter.aiplatform.base.process.domain.model.MainChainDefinition;
import com.aieducenter.aiplatform.base.process.domain.model.StageEntry;

/**
 * A3 主链夹具（A3 §2.2）：需求梳理→〔需求确认·用户〕→Demo→〔Demo 确认·用户〕
 * →开发→测试→〔开发完成确认·开发平台〕→验收→〔验收·用户〕→关闭（终态）。
 * docs/11 的七步在本平台收口为六段一终态（交付段并入收口，A3 附：连带修订）。
 *
 * <p>门禁口径（A3 §2.4）：需求梳理/Demo/开发完成门 = 1，验收门 = 0（验收段无
 * agent 任务）；开发→测试无门（创建首个测试任务时推进，A3 §2.3）——由本夹具
 * 的条目形状直接表达。产物清单 v1 仅需求梳理段 PRD.md（A3 §2.4）。</p>
 */
public final class A3MainChainFixture {

    public static final String REQUIREMENT = "REQUIREMENT";
    public static final String DEMO = "DEMO";
    public static final String DEVELOPMENT = "DEVELOPMENT";
    public static final String TEST = "TEST";
    public static final String ACCEPTANCE = "ACCEPTANCE";
    public static final String DONE = "DONE";

    public static final String ACTOR_USER = "用户";
    public static final String ACTOR_PLATFORM = "开发平台";

    private A3MainChainFixture() {
    }

    /**
     * A3 主链七步四门定义（六段一终态、四扇门）。
     */
    public static MainChainDefinition mainChain() {
        return new MainChainDefinition(List.of(
                StageEntry.of(REQUIREMENT, "需求梳理", "BA", List.of("PRD.md"),
                        new ExitGate(ACTOR_USER, 1)),
                StageEntry.of(DEMO, "Demo", "DEMO", null,
                        new ExitGate(ACTOR_USER, 1)),
                StageEntry.of(DEVELOPMENT, "开发", "DEV", null, null),
                StageEntry.of(TEST, "测试", null, null,
                        new ExitGate(ACTOR_PLATFORM, 1)),
                StageEntry.of(ACCEPTANCE, "验收", null, null,
                        new ExitGate(ACTOR_USER, 0)),
                StageEntry.terminalOf(DONE, "关闭")));
    }
}
