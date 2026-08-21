package com.aieducenter.aiplatform.base.agentengine.infrastructure.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * run 级五档求和（票 #20 验收：模拟逐步增量 → 总量上报的求和半边）——OpenCode 无
 * 总用量字段，tokens 为每个 step-finish 的逐步增量（A1 §2.3：求和归适配器内部）。
 */
class RunUsageAccumulatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void given_no_step_finish_when_total_then_zero() throws Exception {
        RunUsageAccumulator accumulator = new RunUsageAccumulator();

        assertThat(accumulator.total()).isEqualTo(TokenUsage.ZERO);
    }

    @Test
    void given_incremental_steps_when_total_then_five_buckets_summed() throws Exception {
        RunUsageAccumulator accumulator = new RunUsageAccumulator();
        // 逐步增量：两步各带五档增量（含 cache 读写与 reasoning），总量 = 逐档相加
        accumulator.addStepFinish(step(100, 50, 200, 10, 30));
        accumulator.addStepFinish(step(80, 60, 120, 0, 20));

        assertThat(accumulator.total()).isEqualTo(new TokenUsage(180, 110, 320, 10, 50));
        // 互斥分解口径：total = 五档加和（= 提供方 total 口径）
        assertThat(accumulator.total().total()).isEqualTo(670L);
    }

    @Test
    void given_partial_token_fields_when_add_then_missing_counted_zero() throws Exception {
        RunUsageAccumulator accumulator = new RunUsageAccumulator();
        // 防御式解析：缺 cache/reasoning 字段按 0 计（无数据不造数）
        accumulator.addStepFinish(mapper.readTree("""
                {"type":"step-finish","tokens":{"input":10,"output":5}}
                """));

        assertThat(accumulator.total()).isEqualTo(new TokenUsage(10, 5, 0, 0, 0));
    }

    private com.fasterxml.jackson.databind.JsonNode step(long input, long output,
                                                         long cacheRead, long cacheWrite,
                                                         long reasoning) throws Exception {
        return mapper.readTree("""
                {"type":"step-finish","tokens":{
                  "input":%d,"output":%d,
                  "cache":{"read":%d,"write":%d},
                  "reasoning":%d}}
                """.formatted(input, output, cacheRead, cacheWrite, reasoning));
    }
}
