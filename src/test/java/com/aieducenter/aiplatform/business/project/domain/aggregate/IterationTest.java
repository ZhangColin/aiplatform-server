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
}
