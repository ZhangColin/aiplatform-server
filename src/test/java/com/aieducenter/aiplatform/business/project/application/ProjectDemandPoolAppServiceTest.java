package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.CartisanException;

import com.aieducenter.aiplatform.business.project.application.dto.command.AddDemandEntryCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.DemandPoolEntryResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.DemandEntryKind;
import com.aieducenter.aiplatform.business.project.domain.enums.DemandSource;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 需求池用例（片5c 验收，A3 §4）：随时可记（验收前后/期开期关）、条目字段齐全、
 * 清单新→旧；无状态列语义（记录不等同开工）。库行为（真实 PG）；域不变量归
 * DemandPoolEntryTest。
 */
@SpringBootTest
class ProjectDemandPoolAppServiceTest {

    @Autowired
    private ProjectDemandPoolAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_demand_pool_entries");
        jdbcTemplate.update("DELETE FROM prj_iterations");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_entry_with_all_fields_when_add_then_row_complete() throws Exception {
        Long projectId = persistedProject();

        DemandPoolEntryResponse response = asUser(42L, () -> appService.add(projectId,
                new AddDemandEntryCommand(" 支持导出 Excel ", DemandEntryKind.REQUIREMENT,
                        DemandSource.USER)));

        // 条目字段齐全（A3 §4）：content/kind/source/created_by/created_at
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT project_id, content, kind, source, created_by, created_at "
                        + "FROM prj_demand_pool_entries WHERE id = ?",
                Long.parseLong(response.id()));
        assertThat(((Number) row.get("project_id")).longValue()).isEqualTo(projectId);
        assertThat(row.get("content")).isEqualTo("支持导出 Excel");
        assertThat(row.get("kind")).isEqualTo(1);
        assertThat(row.get("source")).isEqualTo(1);
        assertThat(row.get("created_by")).isEqualTo(42L);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(response.kindName()).isEqualTo("需求");
        assertThat(response.sourceName()).isEqualTo("用户");
    }

    @Test
    void given_entry_without_kind_and_source_when_add_then_null_kind_default_user() throws Exception {
        Long projectId = persistedProject();

        DemandPoolEntryResponse response = asUser(null, () -> appService.add(projectId,
                new AddDemandEntryCommand("首页加载偏慢", null, null)));

        // kind 可空（不强分类）；source 缺省用户；无会话上下文 created_by 空
        assertThat(response.kind()).isNull();
        assertThat(response.kindName()).isNull();
        assertThat(response.source()).isEqualTo(DemandSource.USER);
        assertThat(response.createdBy()).isNull();
    }

    @Test
    void given_bug_from_acceptance_when_add_then_kind_bug_source_acceptance() {
        Long projectId = persistedProject();

        DemandPoolEntryResponse response = appService.add(projectId, new AddDemandEntryCommand(
                "下单后库存不扣减", DemandEntryKind.BUG, DemandSource.ACCEPTANCE));

        assertThat(response.kind()).isEqualTo(DemandEntryKind.BUG);
        assertThat(response.source()).isEqualTo(DemandSource.ACCEPTANCE);
    }

    @Test
    void given_blank_content_when_add_then_prj_012_no_row() {
        Long projectId = persistedProject();

        // 留痕不变量兜底（REST 面 @NotBlank 先行同码）
        assertThatThrownBy(() -> appService.add(projectId,
                new AddDemandEntryCommand(" ", null, null)))
                .isInstanceOf(CartisanException.class)
                .hasMessageContaining(ProjectMessage.DEMAND_CONTENT_BLANK.message());
        assertThat(entryCount()).isZero();
    }

    @Test
    void given_missing_project_when_add_then_prj_001() {
        assertThatThrownBy(() -> appService.add(-1L,
                new AddDemandEntryCommand("加个搜索", null, null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_entries_when_entries_then_newest_first() throws Exception {
        Long projectId = persistedProject();
        asUser(1L, () -> appService.add(projectId,
                new AddDemandEntryCommand("第一条", null, null)));
        Thread.sleep(10); // 记录时间区分（created_at 业务时间）
        appService.add(projectId, new AddDemandEntryCommand("第二条", null, null));

        List<DemandPoolEntryResponse> entries = appService.entries(projectId);

        assertThat(entries).extracting(DemandPoolEntryResponse::content)
                .containsExactly("第二条", "第一条"); // 新→旧
    }

    @Test
    void given_entries_of_other_project_when_entries_then_not_leaked() {
        Long projectId = persistedProject();
        Long otherProject = projectRepository.save(Project.create("别的项目", ProjectType.WEBSITE,
                "opencode", 9601L, null)).getId();
        appService.add(otherProject, new AddDemandEntryCommand("别家的需求", null, null));

        assertThat(appService.entries(projectId)).isEmpty(); // 按项目隔离
    }

    @Test
    void given_missing_project_when_entries_then_prj_001() {
        assertThatThrownBy(() -> appService.entries(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- 测试数据 ----------

    private Long persistedProject() {
        return projectRepository.save(Project.create("需求池测试", ProjectType.WEBSITE,
                "opencode", 9600L, null)).getId();
    }

    private long entryCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prj_demand_pool_entries",
                Long.class);
    }

    /** 记录人以会话上下文注入（created_by 从 RequestContext 取）。 */
    private <T> T asUser(Long userId, RequestContextCall<T> call) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, userId, "demand-test", null, null),
                call::get);
    }

    @FunctionalInterface
    private interface RequestContextCall<T> {
        T get();
    }
}
