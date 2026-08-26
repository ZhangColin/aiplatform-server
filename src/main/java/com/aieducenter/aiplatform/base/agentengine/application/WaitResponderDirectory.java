package com.aieducenter.aiplatform.base.agentengine.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.port.CodingAgentAdapter;
import com.aieducenter.aiplatform.base.agentengine.domain.port.WaitResponder;

/**
 * 等待点答复派发目录（#48）：按 engine 名索引 {@link WaitResponder}——编码引擎经
 * {@link AgentEngineRegistry} 权威登记（同一实例，行为不变），对话智能体等非编码
 * 内核以裸 {@link WaitResponder} bean 并入（不进编码引擎能力矩阵，ADR-0002 双轨
 * 分野：Project.engine 词汇只指编码引擎，agentscope 是平台进程内对话内核）。
 *
 * <p>启动即校验：engine() 重名且实例不同 → fail-fast（双实现冲突）；同名同实例
 * （编码引擎既在 registry 又被 Spring 收进 responder 列表）幂等跳过。</p>
 */
@Component
public class WaitResponderDirectory {

    private final Map<String, WaitResponder> responders = new LinkedHashMap<>();

    public WaitResponderDirectory(List<CodingAgentAdapter> codingAdapters,
                                  List<WaitResponder> responders) {
        for (CodingAgentAdapter adapter : codingAdapters) {
            WaitResponder previous = this.responders.put(adapter.engine(), adapter);
            if (previous != null) {
                throw new IllegalStateException("编码引擎重名登记: " + adapter.engine());
            }
        }
        for (WaitResponder responder : responders) {
            WaitResponder existing = this.responders.get(responder.engine());
            if (existing == responder) {
                continue;
            }
            if (existing != null) {
                throw new IllegalStateException("等待点答复通道重名双实现: "
                        + responder.engine());
            }
            this.responders.put(responder.engine(), responder);
        }
    }

    /** 按名取答复通道；未登记抛 AGT_001（404）。 */
    public WaitResponder require(String engine) {
        WaitResponder responder = responders.get(engine);
        if (responder == null) {
            throw new ApplicationException(AgentEngineMessage.ENGINE_NOT_FOUND);
        }
        return responder;
    }
}
