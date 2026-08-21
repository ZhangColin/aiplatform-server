package com.aieducenter.aiplatform.business.project.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目聚合不变量（片5a：业务字段 + 工作区引用 + 归属；状态机主体在期，不在此测）。
 */
class ProjectTest {

    @Test
    void given_valid_fields_when_create_then_defaults_applied() {
        Project project = Project.create("官网 demo", null, "opencode", 100L, 200L);

        assertThat(project.getName()).isEqualTo("官网 demo");
        assertThat(project.getType()).isEqualTo(ProjectType.WEBSITE); // 类型缺省官网
        assertThat(project.getEngine()).isEqualTo("opencode");
        assertThat(project.getWorkspaceId()).isEqualTo(100L);
        assertThat(project.getOwnerAccountId()).isEqualTo(200L);
        assertThat(project.getArchivedAt()).isNull(); // 未归档
    }

    @Test
    void given_explicit_type_when_create_then_kept() {
        Project project = Project.create("商城", ProjectType.ECOMMERCE, "dsh", 1L, null);

        assertThat(project.getType()).isEqualTo(ProjectType.ECOMMERCE);
        assertThat(project.getOwnerAccountId()).isNull(); // 归属可空（无会话上下文）
    }

    @Test
    void given_blank_name_when_create_then_domain_error() {
        assertThatThrownBy(() -> Project.create(" ", ProjectType.WEBSITE, "opencode", 1L, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NAME_BLANK.message());
    }

    @Test
    void given_blank_engine_or_null_workspace_when_create_then_domain_error() {
        assertThatThrownBy(() -> Project.create("官网", ProjectType.WEBSITE, " ", 1L, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> Project.create("官网", ProjectType.WEBSITE, "opencode", null, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_FIELDS_INCOMPLETE.message());
    }

    @Test
    void given_unarchived_when_archive_then_archived_at_set() {
        Project project = Project.create("官网 demo", ProjectType.WEBSITE, "opencode", 1L, null);

        project.archive();

        assertThat(project.getArchivedAt()).isNotNull(); // 单向终点落定
    }

    @Test
    void given_archived_when_archive_again_then_domain_error() {
        Project project = Project.create("官网 demo", ProjectType.WEBSITE, "opencode", 1L, null);
        project.archive();

        assertThatThrownBy(project::archive)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
    }
}
