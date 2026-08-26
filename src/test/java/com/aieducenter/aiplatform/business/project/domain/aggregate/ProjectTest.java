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
    void given_new_project_when_create_then_prd_not_produced() {
        Project project = Project.create("官网 demo", ProjectType.WEBSITE, "opencode", 1L, null);

        assertThat(project.getPrdProducedAt()).isNull(); // NULL = PRD 未产出（#41 状态位）
    }

    @Test
    void given_prd_saved_when_markPrdProduced_then_timestamp_set() {
        Project project = Project.create("官网 demo", ProjectType.WEBSITE, "opencode", 1L, null);

        project.markPrdProduced();

        // 置位含产出/更新时间戳；savePrd 修订再执行即刷新为最近写出（G1 谓词查非空）
        assertThat(project.getPrdProducedAt()).isNotNull();
    }

    @Test
    void given_llm_name_when_rename_then_name_changed() {
        // #39：LLM 取名完成落位（占位 → 生成名）；改名端点（#43）复用同一行为
        Project project = Project.create(Project.PLACEHOLDER_NAME, null, "opencode", 1L, null);

        project.rename("品牌官网");

        assertThat(project.getName()).isEqualTo("品牌官网");
    }

    @Test
    void given_blank_name_when_rename_then_domain_error() {
        Project project = Project.create(Project.PLACEHOLDER_NAME, null, "opencode", 1L, null);

        assertThatThrownBy(() -> project.rename(" "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NAME_BLANK.message());
    }

    @Test
    void given_placeholder_name_when_rename_if_placeholder_then_renamed_and_true() {
        Project project = Project.create(Project.PLACEHOLDER_NAME, null, "opencode", 1L, null);

        boolean renamed = project.renameIfPlaceholder("品牌官网");

        assertThat(renamed).isTrue(); // 占位守卫放行（LLM 取名落位）
        assertThat(project.getName()).isEqualTo("品牌官网");
    }

    @Test
    void given_user_renamed_name_when_rename_if_placeholder_then_kept_and_false() {
        // 取名在飞时用户已改名（#43）→ 不覆写（守卫是聚合规则，非编排判断）
        Project project = Project.create(Project.PLACEHOLDER_NAME, null, "opencode", 1L, null);
        project.rename("我起的名字");

        boolean renamed = project.renameIfPlaceholder("LLM 的名字");

        assertThat(renamed).isFalse();
        assertThat(project.getName()).isEqualTo("我起的名字");
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
