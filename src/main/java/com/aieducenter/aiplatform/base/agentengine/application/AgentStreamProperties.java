package com.aieducenter.aiplatform.base.agentengine.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * agent 流通道配置（#56，前缀 {@code app.agent-stream}）：近期帧重放缓冲容量。
 * 容量上界即通道内存上界——长跑 run 不失控；缓冲为单实例内存态（重启即失，
 * 多实例化时需重估，见 B0 蓝图 §3 升级路径）。
 */
@Component
@ConfigurationProperties(prefix = "app.agent-stream")
public class AgentStreamProperties {

    /** 近期帧重放缓冲容量（每通道有界环形；新连接补发的最近帧数上限）。 */
    private int replayDepth = 1000;

    public int getReplayDepth() {
        return replayDepth;
    }

    public void setReplayDepth(int replayDepth) {
        this.replayDepth = replayDepth;
    }
}
