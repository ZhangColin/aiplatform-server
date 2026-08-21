package com.aieducenter.aiplatform.business.project.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.business.project.domain.enums.DemandEntryKind;
import com.aieducenter.aiplatform.business.project.domain.enums.DemandSource;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 需求池条目不变量（片5c，A3 §4）：内容必填、kind 可空、来源必填、记录时间落定。
 */
class DemandPoolEntryTest {

    @Test
    void given_content_and_kind_when_entry_then_fields_kept() {
        DemandPoolEntry entry = DemandPoolEntry.entryOf(100L, " 支持暗黑模式 ",
                DemandEntryKind.REQUIREMENT, DemandSource.USER, 42L);

        assertThat(entry.getProjectId()).isEqualTo(100L);
        assertThat(entry.getContent()).isEqualTo("支持暗黑模式"); // 首尾空白剔除
        assertThat(entry.getKind()).isEqualTo(DemandEntryKind.REQUIREMENT);
        assertThat(entry.getSource()).isEqualTo(DemandSource.USER);
        assertThat(entry.getCreatedBy()).isEqualTo(42L);
        assertThat(entry.getCreatedAt()).isNotNull(); // 记录时间在入池时取定
    }

    @Test
    void given_no_kind_when_entry_then_kept_null() {
        // 收件时不强分类（随手记一条是常态，A3 §4 kind 可空）
        DemandPoolEntry entry = DemandPoolEntry.entryOf(100L, "首页加载偏慢", null,
                DemandSource.ACCEPTANCE, null);

        assertThat(entry.getKind()).isNull();
        assertThat(entry.getCreatedBy()).isNull(); // 无会话上下文可空
    }

    @Test
    void given_blank_content_when_entry_then_domain_error() {
        assertThatThrownBy(() -> DemandPoolEntry.entryOf(100L, " ", DemandEntryKind.BUG,
                DemandSource.TEST, 1L))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.DEMAND_CONTENT_BLANK.message());
    }

    @Test
    void given_null_project_or_source_when_entry_then_domain_error() {
        assertThatThrownBy(() -> DemandPoolEntry.entryOf(null, "加个搜索", null,
                DemandSource.USER, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> DemandPoolEntry.entryOf(100L, "加个搜索", null, null, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_FIELDS_INCOMPLETE.message());
    }
}
