package com.aieducenter.aiplatform.base.agentengine.application;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.RunResult;
import com.aieducenter.aiplatform.base.agentengine.domain.port.CodingAgentAdapter;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 引擎注册表（票 #20 验收：能力矩阵如实暴露）：按端口收集显式注册（不靠 bean 名）、
 * 未登记名 404、缺省引擎、重名 fail-fast。适配器以最小替身充数——注册表只认端口。
 */
class AgentEngineRegistryTest {

    private final AgentEngineRegistry registry =
            new AgentEngineRegistry(List.of(new StubAdapter("opencode", true, true),
                    new StubAdapter("dsh", false, false)));

    @Test
    void given_registered_engines_when_matrix_then_capabilities_honest() {
        List<AgentEngineRegistry.EngineInfo> matrix = registry.matrix();

        assertThat(matrix).extracting(AgentEngineRegistry.EngineInfo::name)
                .containsExactly("opencode", "dsh");
        // dsh headless：无问答无权限——如实暴露（A1 §1.5），不猜
        assertThat(registry.require("dsh").info().questionSupported()).isFalse();
        assertThat(registry.require("dsh").info().permissionSupported()).isFalse();
        assertThat(registry.require("opencode").info().questionSupported()).isTrue();
        assertThat(matrix.get(0).label()).isEqualTo("stub-opencode");
    }

    @Test
    void given_unknown_engine_when_require_then_404() {
        assertThatThrownBy(() -> registry.require("codex"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.ENGINE_NOT_FOUND.message());
    }

    @Test
    void given_no_engine_when_default_then_platform_default() {
        assertThat(registry.defaultEngine().info().name())
                .isEqualTo(AgentEngineRegistry.DEFAULT_ENGINE);
    }

    @Test
    void given_duplicate_engine_names_when_register_then_fail_fast() {
        assertThatThrownBy(() -> new AgentEngineRegistry(
                List.of(new StubAdapter("opencode", true, true),
                        new StubAdapter("opencode", false, false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("opencode");
    }

    /** 端口替身：名字与能力可配置。 */
    private static final class StubAdapter implements CodingAgentAdapter {

        private final String engine;
        private final boolean questions;
        private final boolean permissions;

        StubAdapter(String engine, boolean questions, boolean permissions) {
            this.engine = engine;
            this.questions = questions;
            this.permissions = permissions;
        }

        @Override
        public String engine() {
            return engine;
        }

        @Override
        public String label() {
            return "stub-" + engine;
        }

        @Override
        public String note() {
            return "测试替身";
        }

        @Override
        public boolean supportsQuestions() {
            return questions;
        }

        @Override
        public boolean supportsPermissions() {
            return permissions;
        }

        @Override
        public RunResult runTask(WorkspaceHandle handle, AgentTaskCommand command,
                                 java.util.function.Consumer<
                                         com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent> sink) {
            return RunResult.rejected(command.runId());
        }

        @Override
        public List<java.util.Map<String, Object>> pendingQuestions(
                WorkspaceHandle handle, String sessionId) {
            return List.of();
        }

        @Override
        public void replyQuestions(WorkspaceHandle handle, String sessionId, String requestId,
                                   List<List<String>> answers) {
        }

        @Override
        public void replyPermission(WorkspaceHandle handle, String sessionId,
                                    String permissionId, boolean approve) {
        }

        @Override
        public boolean abort(WorkspaceHandle handle, String sessionId) {
            return false;
        }

        @Override
        public boolean health(WorkspaceHandle handle) {
            return false;
        }
    }
}
