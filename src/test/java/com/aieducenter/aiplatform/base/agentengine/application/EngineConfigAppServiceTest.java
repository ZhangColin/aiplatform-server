package com.aieducenter.aiplatform.base.agentengine.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.dto.response.EngineConfigResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.EngineConfig;
import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentTaskCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.RunResult;
import com.aieducenter.aiplatform.base.agentengine.domain.port.CodingAgentAdapter;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.EngineConfigRepository;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全局引擎配置用例（票 #42 验收）：无配置回落注册表缺省（GET 不 500）、切换
 * 值 ∈ 注册表否则 400 AGT_009、单行 upsert（首切换落库 / 既有行原地换值）、
 * 配置漂移（库值引擎已下线）回落缺省——创建路径与任务下发的缺省解析入口。
 */
@ExtendWith(MockitoExtension.class)
class EngineConfigAppServiceTest {

    @Mock
    private EngineConfigRepository configRepository;

    private final AgentEngineRegistry registry = new AgentEngineRegistry(
            List.of(new StubAdapter("opencode"), new StubAdapter("dsh")));

    private EngineConfigAppService appService;

    @BeforeEach
    void setUp() {
        appService = new EngineConfigAppService(configRepository, registry);
    }

    @Test
    void given_never_configured_when_current_then_registry_default_no_error() {
        when(configRepository.findById(EngineConfig.SINGLETON_ID)).thenReturn(Optional.empty());

        // 从未配置：返回注册表缺省 opencode，不报错（验收：不 500）
        assertThat(appService.current()).isEqualTo(new EngineConfigResponse("opencode"));
        assertThat(appService.activeEngineName()).isEqualTo("opencode");
    }

    @Test
    void given_registered_engine_when_switch_then_singleton_row_saved() {
        when(configRepository.findById(EngineConfig.SINGLETON_ID)).thenReturn(Optional.empty());

        EngineConfigResponse response = appService.switchTo("dsh");

        assertThat(response.engine()).isEqualTo("dsh");
        // 首次切换：落单例行（id 钉死 1，非 TSID）
        verify(configRepository).save(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getId()).isEqualTo(EngineConfig.SINGLETON_ID);
        assertThat(rowCaptor.getValue().getActiveEngine()).isEqualTo("dsh");
    }

    @Test
    void given_existing_row_when_switch_then_engine_replaced_in_place() {
        EngineConfig existing = EngineConfig.global("opencode");
        when(configRepository.findById(EngineConfig.SINGLETON_ID))
                .thenReturn(Optional.of(existing));

        appService.switchTo("dsh");

        verify(configRepository).save(existing); // 原地换值，不造新行
        assertThat(existing.getActiveEngine()).isEqualTo("dsh");
    }

    @Test
    void given_unknown_engine_when_switch_then_400_agt_009() {
        assertThatThrownBy(() -> appService.switchTo("codex"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(AgentEngineMessage.ENGINE_CONFIG_UNKNOWN.message())
                .extracting(e -> ((ApplicationException) e).getCodeMessage().code())
                .isEqualTo(AgentEngineMessage.ENGINE_CONFIG_UNKNOWN.code());
        verify(configRepository, never()).save(any()); // 非法值不落库
    }

    @Test
    void given_configured_engine_when_active_then_used_for_creation_default() {
        when(configRepository.findById(EngineConfig.SINGLETON_ID))
                .thenReturn(Optional.of(EngineConfig.global("dsh")));

        // 切换后：创建路径与任务下发的缺省来源 = 新配置值（验收：之后新建项目用新引擎）
        assertThat(appService.activeEngineName()).isEqualTo("dsh");
        assertThat(appService.current()).isEqualTo(new EngineConfigResponse("dsh"));
    }

    @Test
    void given_drifted_config_when_active_then_fallback_default() {
        // 配置漂移：库值引擎已下线（不在注册表）——回落缺省，GET/下发不 500，后台重切即恢复
        when(configRepository.findById(EngineConfig.SINGLETON_ID))
                .thenReturn(Optional.of(EngineConfig.global("codex")));

        assertThat(appService.current()).isEqualTo(new EngineConfigResponse("opencode"));
        assertThat(appService.activeEngineName()).isEqualTo("opencode");
    }

    private static final ArgumentCaptor<EngineConfig> rowCaptor =
            ArgumentCaptor.forClass(EngineConfig.class);

    /** 端口替身：名字可配置（注册表只认端口，AgentEngineRegistryTest 同款裁剪）。 */
    private static final class StubAdapter implements CodingAgentAdapter {

        private final String engine;

        StubAdapter(String engine) {
            this.engine = engine;
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
            return true;
        }

        @Override
        public boolean supportsPermissions() {
            return true;
        }

        @Override
        public RunResult runTask(WorkspaceHandle handle, AgentTaskCommand command,
                                 Consumer<AgentEvent> sink) {
            return RunResult.rejected(command.runId());
        }

        @Override
        public List<Map<String, Object>> pendingQuestions(WorkspaceHandle handle, String sessionId) {
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
