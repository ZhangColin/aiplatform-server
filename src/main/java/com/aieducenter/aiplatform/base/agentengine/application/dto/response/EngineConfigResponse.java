package com.aieducenter.aiplatform.base.agentengine.application.dto.response;

/**
 * 全局引擎配置响应（票 #42）：当前生效引擎名（能力矩阵见 /api/agent-engines，
 * 后台切换 UI = 矩阵全量 + 本值标记当前）。
 *
 * @param engine 生效引擎名（注册表键：opencode / dsh）
 */
public record EngineConfigResponse(String engine) {
}
