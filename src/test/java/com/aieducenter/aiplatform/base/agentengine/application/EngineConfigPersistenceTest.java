package com.aieducenter.aiplatform.base.agentengine.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.base.agentengine.application.dto.response.EngineConfigResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.EngineConfig;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.EngineConfigRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局引擎配置落库（票 #42 验收：重启不丢）：agt_engine_config 单行 upsert——
 * 首切换落库、再切换原地换值不增行、无配置回落注册表缺省（不种子）。
 * 「重启接回」与 AgentSessionPersistenceTest 同口径：状态在库，重启后同一查询
 * 照常读到即语义成立。
 */
@SpringBootTest
class EngineConfigPersistenceTest {

    @Autowired
    private EngineConfigAppService appService;

    @Autowired
    private EngineConfigRepository configRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM agt_engine_config");
    }

    @Test
    void given_never_configured_when_current_then_default_without_row() {
        // 无配置 = 无行：回落注册表缺省 opencode，不种子（代码缺省与库值不劈叉）
        assertThat(appService.current()).isEqualTo(new EngineConfigResponse("opencode"));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agt_engine_config", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void given_first_switch_when_saved_then_singleton_row_addressable() {
        appService.switchTo("dsh");

        // 真实状态：单行落库（id 钉死 1），重启后同一寻址照常读到
        String engine = jdbcTemplate.queryForObject(
                "SELECT active_engine FROM agt_engine_config WHERE id = "
                        + EngineConfig.SINGLETON_ID, String.class);
        assertThat(engine).isEqualTo("dsh");
        assertThat(configRepository.findById(EngineConfig.SINGLETON_ID))
                .hasValueSatisfying(config -> assertThat(config.getActiveEngine())
                        .isEqualTo("dsh"));
    }

    @Test
    void given_second_switch_when_saved_then_row_replaced_not_multiplied() {
        appService.switchTo("dsh");
        appService.switchTo("opencode");

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agt_engine_config", Integer.class);
        assertThat(rows).isEqualTo(1); // 原地换值，切换不积累行
        assertThat(appService.current()).isEqualTo(new EngineConfigResponse("opencode"));
    }
}
