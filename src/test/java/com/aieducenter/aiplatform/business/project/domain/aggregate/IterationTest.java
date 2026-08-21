package com.aieducenter.aiplatform.business.project.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 期聚合不变量（A3 §2.1：状态机主体在期——起始 BA/OPEN/计数 0；收口后不再计数）。
 */
class IterationTest {

    @Test
    void given_first_iteration_when_open_then_ba_open_zero_count() {
        Iteration iteration = Iteration.open(1L, Iteration.FIRST_SEQ, ProjectMainChain.firstStage());

        assertThat(iteration.getProjectId()).isEqualTo(1L);
        assertThat(iteration.getSeq()).isEqualTo(1);
        assertThat(iteration.getStage()).isEqualTo(ProjectMainChain.STAGE_BA);
        assertThat(iteration.getStatus()).isEqualTo(IterationStatus.OPEN);
        assertThat(iteration.getStageTaskCount()).isZero();
        assertThat(iteration.getClosedAt()).isNull();
    }

    @Test
    void given_open_iteration_when_record_stage_task_then_count_increases() {
        Iteration iteration = Iteration.open(1L, Iteration.FIRST_SEQ, ProjectMainChain.firstStage());

        iteration.recordStageTask();
        iteration.recordStageTask();

        assertThat(iteration.getStageTaskCount()).isEqualTo(2);
    }

    @Test
    void given_incomplete_fields_when_open_then_domain_error() {
        assertThatThrownBy(() -> Iteration.open(null, 1, ProjectMainChain.STAGE_BA))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> Iteration.open(1L, 1, " "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_FIELDS_INCOMPLETE.message());
    }

    @Test
    void given_counted_stage_when_advance_to_then_stage_moves_and_count_resets() {
        Iteration iteration = Iteration.open(1L, Iteration.FIRST_SEQ, ProjectMainChain.STAGE_BA);
        iteration.recordStageTask();

        iteration.advanceTo(ProjectMainChain.STAGE_DEMO);

        // 下一阶段门禁从 0 起算（A3 §2.4 计数按阶段）
        assertThat(iteration.getStage()).isEqualTo(ProjectMainChain.STAGE_DEMO);
        assertThat(iteration.getStageTaskCount()).isZero();
        assertThat(iteration.getStatus()).isEqualTo(IterationStatus.OPEN);
    }

    @Test
    void given_acceptance_passed_when_close_then_terminal_closed_with_timestamp() {
        Iteration iteration = Iteration.open(1L, Iteration.FIRST_SEQ,
                ProjectMainChain.STAGE_ACCEPTANCE);

        iteration.close(ProjectMainChain.STAGE_CLOSED);

        // 验收门通过即收口（A3 §2.2：无交付段——期 CLOSED，项目已交付是派生投影）
        assertThat(iteration.getStage()).isEqualTo(ProjectMainChain.STAGE_CLOSED);
        assertThat(iteration.getStatus()).isEqualTo(IterationStatus.CLOSED);
        assertThat(iteration.getClosedAt()).isNotNull();

        // 收口后无过程迁移，也不再计数（工具与过程正交）
        assertThatThrownBy(() -> iteration.advanceTo(ProjectMainChain.STAGE_BA))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.ITERATION_NOT_OPEN.message());
        assertThatThrownBy(() -> iteration.close(ProjectMainChain.STAGE_CLOSED))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.ITERATION_NOT_OPEN.message());
        int countBefore = iteration.getStageTaskCount();
        iteration.recordStageTask();
        assertThat(iteration.getStageTaskCount()).isEqualTo(countBefore);
    }

    @Test
    void given_open_iteration_when_advance_with_blank_stage_then_domain_error() {
        Iteration iteration = Iteration.open(1L, Iteration.FIRST_SEQ, ProjectMainChain.STAGE_BA);

        assertThatThrownBy(() -> iteration.advanceTo(" "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> iteration.close(null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_FIELDS_INCOMPLETE.message());
    }
}
