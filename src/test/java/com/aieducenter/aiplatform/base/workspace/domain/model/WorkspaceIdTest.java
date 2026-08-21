package com.aieducenter.aiplatform.base.workspace.domain.model;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工作区标识：正数不变量（WSP_004）+ TSID 生成 + 数值/字符串形互转。
 */
class WorkspaceIdTest {

    @Test
    void given_non_positive_id_when_construct_then_rejected() {
        assertThatThrownBy(() -> new WorkspaceId(0))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("工作区标识不合法");
        assertThatThrownBy(() -> new WorkspaceId(-1))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void given_generate_when_twice_then_distinct_ids() {
        assertThat(WorkspaceId.generate()).isNotEqualTo(WorkspaceId.generate());
    }

    @Test
    void given_numeric_string_when_of_then_id_and_value_round_trip() {
        WorkspaceId id = WorkspaceId.of("42");

        assertThat(id.id()).isEqualTo(42L);
        assertThat(id.value()).isEqualTo("42");
        assertThat(WorkspaceId.of(id.value())).isEqualTo(id);
    }

    @Test
    void given_non_numeric_string_when_of_then_number_format_exception() {
        // 调用方（应用层 parseId）负责兜底为 404，此处只验原始行为
        assertThatThrownBy(() -> WorkspaceId.of("not-a-tsid"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void given_id_when_to_string_then_plain_value_form() {
        assertThat(WorkspaceId.of("42").toString()).isEqualTo("42");
    }
}
