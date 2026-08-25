package com.aieducenter.aiplatform.base.chatagent.domain.model;

/**
 * 对话过程回调（#44 最小骨架只暴露文本增量；事件 → 流帧全量桥接归 #45）。
 */
public interface ChatAgentProgress {

    ChatAgentProgress NONE = delta -> {
    };

    /** 模型输出的增量文本片段（TextBlock delta） */
    void onTextDelta(String delta);
}
