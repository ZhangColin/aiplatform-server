package com.aieducenter.aiplatform.base.agentengine.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitStatus;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentWaitRepository;

/**
 * 等待点读侧用例（#48 拆出）：答复通道按 (sessionId, engineRef) 取回挂起载荷——
 * 与 {@link AgentWaitAppService}（登记/settle 派发/守卫）分立，答复通道（如对话智能体
 * 的 {@code WaitResponder} 实现）依赖本读侧即可，不与 settle 派发目录构成环
 * （directory → responder → 本服务 → repository）。
 */
@Service
public class AgentWaitQueryAppService {

    private final AgentWaitRepository waitRepository;

    public AgentWaitQueryAppService(AgentWaitRepository waitRepository) {
        this.waitRepository = waitRepository;
    }

    /**
     * 按引擎引用单查 PENDING 等待点（settle 派发时答复通道的恢复输入：body 内
     * toolCalls/模型/关联字段——跨重启可重建续跑）。
     */
    @Transactional(readOnly = true)
    public Optional<WaitPointResponse> pendingByRef(String sessionId, String engineRef) {
        return waitRepository
                .findBySessionIdAndEngineRefAndStatus(sessionId, engineRef, WaitStatus.PENDING)
                .map(WaitPointResponse::from);
    }
}
